(ns kmet.tui.theme
  "Theme system — identical structure to pi's theme.ts.
   EDN-only loading with the same :vars/:colors schema and color values
   (hex, OKHSL, OKLCH, 256-color index, variable refs, terminal default)
   as pi's JSON.

   The TUI layer's theme leaf: the TUI mode row loads the configured paths
   (`load-theme-paths!`) and the components that style themselves read the
   rest. It moved back here when theme loading left the process world; app
   business code no longer reads the store."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tui.highlight :as hl]
            [kmet.tui.terminal-image :as term-image]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Color tokens — matching pi's ThemeColor / ThemeBg exactly
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const FG-TOKENS
  [:accent :border :border-accent :border-muted
   :success :error :warning :muted :dim :text :thinking-text
   :scrollbar-track :scrollbar-thumb :search-match-text
   :user-message-text :custom-message-text :custom-message-label
   :tool-title :tool-output
   :md-heading :md-link :md-link-url :md-code
   :md-code-block :md-code-block-border :md-quote :md-quote-border
   :md-hr :md-list-bullet
   :tool-diff-added :tool-diff-removed :tool-diff-context
   :syntax-comment :syntax-keyword :syntax-function
   :syntax-variable :syntax-string :syntax-number
   :syntax-type :syntax-operator :syntax-punctuation
   :thinking-off :thinking-minimal :thinking-low
   :thinking-medium :thinking-high :thinking-xhigh :thinking-max
   :bash-mode])

(def ^:const BG-TOKENS
  [:selected-bg :search-match-bg :user-message-bg :custom-message-bg
   :tool-pending-bg :tool-success-bg :tool-error-bg])

;; ═══════════════════════════════════════════════════════════════════════════
;; Theme record — wraps fg/bg ANSI maps with API matching pi's Theme class
;; ═══════════════════════════════════════════════════════════════════════════

(defrecord Theme [name
                  fg-colors    ;; {keyword → ANSI escape string}
                  bg-colors    ;; {keyword → ANSI escape string}
                  color-mode   ;; :truecolor or :256color
                  source-path] ;; optional path for file-watching

  Object
  (toString [this] (str "#Theme{:name " (:name this) "}")))

;; ═══════════════════════════════════════════════════════════════════════════
;; Color helpers — pi's parseColor / okhslToRgb / oklchToRgb / rgbToAnsi256,
;; plus the fg/bg ANSI escapes built from them.
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private FG-RST "\u001b[39m")
(def ^:private BG-RST "\u001b[49m")

(defn hex->rgb
  "Parse #rgb or #rrggbb into {:r :g :b} 0-255 channels (pi: hexToRgb, plus
   the three-digit shorthand)."
  [hex]
  (let [h (subs hex 1)
        h (if (= 3 (count h))
            (apply str (mapcat (fn [digit] [digit digit]) h))
            h)]
    {:r (Integer/parseInt (subs h 0 2) 16)
     :g (Integer/parseInt (subs h 2 4) 16)
     :b (Integer/parseInt (subs h 4 6) 16)}))

;; ─── Oklab / OKHSL / OKLCH → sRGB (pi: packages/tui/src/oklab.ts) ──────────
;;
;; Björn Ottosson's reference implementation (MIT license), ported from pi's
;; oklab.ts. OKHSL and OKLCH are the color formats pi's built-in themes use.

(def ^:private lab->lms
  [[1 0.3963377773761749 0.2158037573099136]
   [1 -0.1055613458156586 -0.0638541728258133]
   [1 -0.0894841775298119 -1.2914855480194092]])

(def ^:private lms->linear-srgb
  [[4.0767416360759583 -3.3077115392580629 0.2309699031821043]
   [-1.2684379732850315 2.6097573492876882 -0.341319376002657]
   [-0.0041960761386756 -0.7034186179359362 1.7076146940746117]])

(def ^:private saturation-fit
  "Per sRGB channel (red, green, blue): the (a, b) half-plane where the
   channel clips first, and the polynomial approximating the maximum
   saturation there."
  [[[-1.8817031 -0.80936501]
    [1.19086277 1.76576728 0.59662641 0.75515197 0.56771245]]
   [[1.8144408 -1.19445267]
    [0.73956515 -0.45954404 0.08285427 0.12541073 -0.14503204]]
   [[0.13110758 1.81333971]
    [1.35733652 -0.00915799 -1.1513021 -0.50559606 0.00692167]]])

(def ^:private okhsl-k1 0.206)
(def ^:private okhsl-k2 0.03)
(def ^:private okhsl-k3 (/ (+ 1 okhsl-k1) (+ 1 okhsl-k2)))

(defn- mat*vec [m v]
  (mapv (fn [row] (reduce + (map * row v))) m))

(defn- oklab->linear-srgb [lab]
  (mat*vec lms->linear-srgb
           (mapv (fn [v] (* v v v)) (mat*vec lab->lms lab))))

(defn- linear->srgb [value]
  (if (> value 0.0031308)
    (- (* 1.055 (Math/pow value (/ 1 2.4))) 0.055)
    (* 12.92 value)))

(defn- linear-srgb->rgb
  "Linear sRGB (0-1, possibly out of gamut) → {:kind :rgb} with rounded
   0-255 channels, clipping like pi's linearSrgbToRgb."
  [linear]
  (let [channel (fn [v] (Math/round (double (* 255 (min 1 (max 0 (linear->srgb v)))))))]
    {:kind :rgb
     :r (channel (nth linear 0))
     :g (channel (nth linear 1))
     :b (channel (nth linear 2))}))

(defn- okhsl->oklab-lightness [x]
  (/ (+ (* x x) (* okhsl-k1 x)) (* okhsl-k3 (+ x okhsl-k2))))

(defn- lms-slopes [a b]
  (mapv (fn [row] (+ (* (nth row 1) a) (* (nth row 2) b))) lab->lms))

(defn- max-saturation
  "Largest saturation (C/L) inside sRGB for hue (a, b): polynomial fit plus
   one Halley step."
  [a b]
  (let [channel (first (keep-indexed
                        (fn [index [[x y] _]]
                          (when (or (= index 2) (> (+ (* x a) (* y b)) 1)) index))
                        saturation-fit))
        weights (nth lms->linear-srgb channel)
        [k0 k1 k2 k3 k4] (second (nth saturation-fit channel))
        saturation (+ k0 (* k1 a) (* k2 b) (* k3 a a) (* k4 a b))
        slopes (lms-slopes a b)
        base (mapv (fn [k] (+ 1 (* saturation k))) slopes)
        dot (fn [values] (reduce + (map * weights values)))
        f (dot (mapv (fn [v] (* v v v)) base))
        f1 (dot (mapv (fn [v k] (* 3 k v v)) base slopes))
        f2 (dot (mapv (fn [v k] (* 6 k k v)) base slopes))]
    (- saturation (/ (* f f1) (- (* f1 f1) (* 0.5 f f2))))))

(defn- cusp
  "Oklab lightness and chroma of the most saturated sRGB color for hue
   (a, b)."
  [a b]
  (let [saturation (max-saturation a b)
        linear (oklab->linear-srgb [1 (* saturation a) (* saturation b)])
        lightness (Math/cbrt (/ 1 (apply max linear)))]
    [lightness (* lightness saturation)]))

(defn- max-chroma
  "Chroma where the constant-lightness line at LIGHTNESS leaves the sRGB
   gamut."
  [a b lightness [cusp-l cusp-c]]
  (if (<= lightness cusp-l)
    (/ (* cusp-c lightness) cusp-l)
    (let [t (/ (* cusp-c (- lightness 1)) (- cusp-l 1))
          slopes (lms-slopes a b)
          lms (mapv (fn [k] (+ lightness (* t k))) slopes)
          cubes (mapv (fn [v] (* v v v)) lms)
          firsts (mapv (fn [k v] (* 3 k v v)) slopes lms)
          seconds (mapv (fn [k v] (* 6 k k v)) slopes lms)
          dot (fn [row values] (reduce + (map * row values)))
          steps (map (fn [row]
                       (let [f (- (dot row cubes) 1)
                             f1 (dot row firsts)
                             f2 (dot row seconds)
                             u (/ f1 (- (* f1 f1) (* 0.5 f f2)))]
                         (if (>= u 0) (- (* f u)) ##Inf)))
                     lms->linear-srgb)]
      (+ t (apply min steps)))))

(defn- chroma-stops
  "OKHSL's chroma reference points at LIGHTNESS and hue (a, b):
   [c0 c-mid c-max]."
  [lightness a b]
  (let [[peak-l peak-c] (cusp a b)
        c-max (max-chroma a b lightness [peak-l peak-c])
        k (/ c-max (min (* lightness (/ peak-c peak-l))
                        (* (- 1 lightness) (/ peak-c (- 1 peak-l)))))
        mid-s-w (+ -4.24894561 (* 5.38770819 b) (* 4.69891013 a))
        mid-s-z (+ -2.13704948 (* -10.02301043 b) (* a mid-s-w))
        mid-s-y (+ -2.19557347 (* 1.75198401 b) (* a mid-s-z))
        mid-s (+ 0.11516993 (/ 1 (+ 7.4477897 (* 4.1590124 b) (* a mid-s-y))))
        mid-t-w (+ 0.00299215 (* -0.45399568 b) (* -0.14661872 a))
        mid-t-z (+ -0.27087943 (* 0.6122399 b) (* a mid-t-w))
        mid-t-y (+ 0.40370612 (* 0.90148123 b) (* a mid-t-z))
        mid-t (+ 0.11239642 (/ 1 (+ 1.6132032 (* -0.68124379 b) (* a mid-t-y))))
        c-mid (* 0.9 k (Math/sqrt (Math/sqrt (/ 1 (+ (/ 1 (Math/pow (* lightness mid-s) 4))
                                                     (/ 1 (Math/pow (* (- 1 lightness) mid-t) 4)))))))
        c0 (Math/sqrt (/ 1 (+ (/ 1 (Math/pow (* lightness 0.4) 2))
                              (/ 1 (Math/pow (* (- 1 lightness) 0.8) 2)))))]
    [c0 c-mid c-max]))

(defn- okhsl->rgb
  "OKHSL → {:kind :rgb} (pi: okhslToRgb). HUE in degrees; SATURATION and
   LIGHTNESS in 0-1, relative to the sRGB gamut at the hue and lightness."
  [hue saturation lightness]
  (let [l (okhsl->oklab-lightness lightness)
        lab (if (and (> l 0) (< l 1) (> saturation 0))
              (let [angle (/ (* 2 Math/PI (mod hue 360)) 360)
                    a (Math/cos angle)
                    b (Math/sin angle)
                    [c0 c-mid c-max] (chroma-stops l a b)
                    chroma (if (< saturation 0.8)
                             (let [t (* 1.25 saturation)
                                   k1 (* 0.8 c0)]
                               (/ (* t k1) (- 1 (* (- 1 (/ k1 c-mid)) t))))
                             (let [t (* 5 (- saturation 0.8))
                                   k1 (/ (* 0.2 c-mid c-mid (Math/pow 1.25 2)) c0)]
                               (+ c-mid (/ (* t k1) (- 1 (* (- 1 (/ k1 (- c-max c-mid))) t))))))]
                [l (* chroma a) (* chroma b)])
              [l 0 0])]
    (linear-srgb->rgb (oklab->linear-srgb lab))))

(defn- oklch->rgb
  "OKLCH → {:kind :rgb} with pi's chroma bisection gamut mapping (pi:
   oklchToRgb). LIGHTNESS in 0-1, CHROMA >= 0, HUE in degrees."
  [lightness chroma hue]
  (let [radians (/ (* hue Math/PI) 180)
        cos (Math/cos radians)
        sin (Math/sin radians)
        at-chroma (fn [c] (oklab->linear-srgb [lightness (* c cos) (* c sin)]))
        in-gamut? (fn [linear] (every? #(and (>= % -1e-7) (<= % 1.0000001)) linear))
        direct (at-chroma chroma)]
    (if (in-gamut? direct)
      (linear-srgb->rgb direct)
      (loop [i 0 low 0.0 high chroma linear (at-chroma 0)]
        (if (= i 20)
          (linear-srgb->rgb linear)
          (let [c (/ (+ low high) 2)
                candidate (at-chroma c)]
            (if (in-gamut? candidate)
              (recur (inc i) c high candidate)
              (recur (inc i) low c linear))))))))

;; ─── Color parsing (pi: parseColor) ────────────────────────────────────────

(def ^:private number-pattern
  "[+-]?(?:[0-9]+(?:[.][0-9]*)?|[.][0-9]+)(?:e[+-]?[0-9]+)?")

(def ^:private oklch-pattern
  (re-pattern (str "^oklch[(]\\s*(" number-pattern ")(%)?\\s+(" number-pattern
                   ")\\s+(" number-pattern ")(?:deg)?\\s*[)]$")))

(def ^:private okhsl-pattern
  (re-pattern (str "^okhsl[(]\\s*(" number-pattern ")(?:deg)?\\s+(" number-pattern
                   ")(%)?\\s+(" number-pattern ")(%)?\\s*[)]$")))

(defn parse-color
  "Parse VALUE the way pi's parseColor does: #rgb/#rrggbb, oklch(...),
   okhsl(...), a 0-255 palette index, or an empty string for the terminal
   default. Returns nil for the terminal default, {:kind :rgb :r :g :b} for
   colors and {:kind :indexed :index n} for palette indices; throws on an
   invalid color string (pi: Invalid color value)."
  [value]
  (cond
    (nil? value) nil
    (number? value) {:kind :indexed :index (long value)}
    (not (string? value)) nil
    (= value "") nil
    :else
    (let [s (str/lower-case value)]
      (or (when (re-matches #"^#([0-9a-f]{3}|[0-9a-f]{6})$" s)
            (assoc (hex->rgb s) :kind :rgb))
          (when-let [[_ lightness percent chroma hue] (re-matches oklch-pattern s)]
            (let [l (/ (parse-double lightness) (if percent 100.0 1.0))
                  c (parse-double chroma)]
              (when (or (neg? l) (> l 1))
                (throw (ex-info (str "l must be between 0 and 1: " l) {:type :invalid-color})))
              (when (neg? c)
                (throw (ex-info (str "c must not be negative: " c) {:type :invalid-color})))
              (oklch->rgb l c (parse-double hue))))
          (when-let [[_ hue saturation s-percent lightness l-percent] (re-matches okhsl-pattern s)]
            (let [sat (/ (parse-double saturation) (if s-percent 100.0 1.0))
                  l (/ (parse-double lightness) (if l-percent 100.0 1.0))]
              (when (or (neg? sat) (> sat 1))
                (throw (ex-info (str "s must be between 0 and 1: " sat) {:type :invalid-color})))
              (when (or (neg? l) (> l 1))
                (throw (ex-info (str "l must be between 0 and 1: " l) {:type :invalid-color})))
              (okhsl->rgb (parse-double hue) sat l)))
          (throw (ex-info (str "Invalid color value: " value) {:type :invalid-color}))))))

;; ─── 256-color approximation and ANSI escapes (pi: rgbToAnsi256, colorAnsi) ─

(def ^:private cube-values [0 95 135 175 215 255])
(def ^:private gray-values (mapv #(+ 8 (* % 10)) (range 24)))

(defn- find-closest
  "Index into VALUES of the value closest to TARGET (pi: findClosest)."
  [values target]
  (first (reduce (fn [[best-index best-distance] [index value]]
                   (let [distance (Math/abs (- (double target) value))]
                     (if (< distance best-distance)
                       [index distance]
                       [best-index best-distance])))
                 [0 ##Inf]
                 (map-indexed vector values))))

(defn- color-distance
  "Squared weighted RGB distance (pi: colorDistance)."
  [[r1 g1 b1] [r2 g2 b2]]
  (let [square (fn [d] (* d d))]
    (+ (* 0.299 (square (- r1 r2)))
       (* 0.587 (square (- g1 g2)))
       (* 0.114 (square (- b1 b2))))))

(defn- rgb->256
  "Closest xterm 256-color palette index; the gray ramp is only preferred
   when it beats the color cube (pi: rgbToAnsi256)."
  [r g b]
  (let [r-index (find-closest cube-values r)
        g-index (find-closest cube-values g)
        b-index (find-closest cube-values b)
        cube-color [(nth cube-values r-index) (nth cube-values g-index) (nth cube-values b-index)]
        cube-index (+ 16 (* 36 r-index) (* 6 g-index) b-index)
        gray (Math/round (double (+ (* 0.299 r) (* 0.587 g) (* 0.114 b))))
        gray-offset (find-closest gray-values gray)
        gray-value (nth gray-values gray-offset)
        spread (- (max r g b) (min r g b))]
    (if (and (< spread 10)
             (< (color-distance [r g b] [gray-value gray-value gray-value])
                (color-distance [r g b] cube-color)))
      (+ 232 gray-offset)
      cube-index)))

(defn- color->ansi
  "The escape that sets PARSED (a parse-color result) in the foreground or
   background slot of COLOR-MODE (pi: colorAnsi)."
  [parsed color-mode background?]
  (let [slot (if background? 48 38)]
    (case (:kind parsed)
      :indexed (str "\u001b[" slot ";5;" (:index parsed) "m")
      :rgb (let [{:keys [r g b]} parsed]
             (if (= color-mode :truecolor)
               (str "\u001b[" slot ";2;" r ";" g ";" b "m")
               (str "\u001b[" slot ";5;" (rgb->256 r g b) "m"))))))

(defn- fg-ansi [value color-mode]
  (if-let [parsed (parse-color value)]
    (color->ansi parsed color-mode false)
    FG-RST))

(defn- bg-ansi [value color-mode]
  (if-let [parsed (parse-color value)]
    (color->ansi parsed color-mode true)
    BG-RST))

;; ═══════════════════════════════════════════════════════════════════════════
;; Theme API — matching pi's theme.fg() / theme.bg() / bold() etc.
;; ═══════════════════════════════════════════════════════════════════════════

(defn fg
  "pi-equivalent: theme.fg(color, text) — wrap text in fg color, reset fg only."
  [theme color-key text]
  (str (get (:fg-colors theme) color-key FG-RST) text FG-RST))

(defn bg
  "pi-equivalent: theme.bg(color, text) — wrap text in bg color, reset bg only."
  [theme color-key text]
  (str (get (:bg-colors theme) color-key BG-RST) text BG-RST))

(defn bold [text] (str "\u001b[1m" text "\u001b[22m"))
(defn dim [text] (str "\u001b[2m" text "\u001b[22m"))
(defn italic [text] (str "\u001b[3m" text "\u001b[23m"))
(defn underline [text] (str "\u001b[4m" text "\u001b[24m"))
(defn inverse [text] (str "\u001b[7m" text "\u001b[27m"))
(defn strikethrough [text] (str "\u001b[9m" text "\u001b[29m"))

(defn get-fg-ansi
  "The ANSI escape that sets COLOR-KEY as foreground (pi: getFgAnsi — throws
   on unknown colors)."
  [theme color-key]
  (or (get (:fg-colors theme) color-key)
      (throw (ex-info (str "Unknown theme color: " color-key)
                      {:type :unknown-theme-color :color color-key}))))

(defn get-bg-ansi
  "The ANSI escape that sets COLOR-KEY as background (pi: getBgAnsi — throws
   on unknown colors)."
  [theme color-key]
  (or (get (:bg-colors theme) color-key)
      (throw (ex-info (str "Unknown theme background color: " color-key)
                      {:type :unknown-theme-color :color color-key}))))

(defn get-color-mode
  "The theme's color mode (:truecolor or :256color) (pi: getColorMode)."
  [theme]
  (:color-mode theme))

(defn get-thinking-border-color
  "A color fn for the thinking border at LEVEL (:off :minimal :low :medium
   :high :xhigh :max) (pi: getThinkingBorderColor). Unknown levels map to
   :thinking-off like pi."
  [theme level]
  (let [k (case level
            :off :thinking-off :minimal :thinking-minimal :low :thinking-low
            :medium :thinking-medium :high :thinking-high :xhigh :thinking-xhigh
            :max :thinking-max
            :thinking-off)]
    (fn [s] (fg theme k s))))

(defn get-bash-mode-border-color
  "A color fn for the bash-mode border (pi: getBashModeBorderColor)."
  [theme]
  (fn [s] (fg theme :bash-mode s)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Syntax highlighting — mirroring pi's getCliHighlightTheme() + highlightCode()
;; ═══════════════════════════════════════════════════════════════════════════

(defn- highlight-formatters
  "Scope keyword → text-styling fn for theme T (pi: buildCliHighlightTheme)."
  [t]
  {:keyword (fn [s] (fg t :syntax-keyword s))
   :symbol (fn [s] (fg t :syntax-variable s))
   :literal (fn [s] (fg t :syntax-number s))
   :number (fn [s] (fg t :syntax-number s))
   :string (fn [s] (fg t :syntax-string s))
   :comment (fn [s] (fg t :syntax-comment s))
   :function (fn [s] (fg t :syntax-function s))
   :attr (fn [s] (fg t :syntax-variable s))
   :variable (fn [s] (fg t :syntax-variable s))
   :meta (fn [s] (fg t :muted s))
   :operator (fn [s] (fg t :syntax-operator s))
   :punctuation (fn [s] (fg t :syntax-punctuation s))
   :tag (fn [s] (fg t :syntax-punctuation s))
   :name (fn [s] (fg t :syntax-keyword s))
   :section (fn [s] (fg t :md-heading s))
   :code (fn [s] (fg t :md-code s))
   :addition (fn [s] (fg t :tool-diff-added s))
   :deletion (fn [s] (fg t :tool-diff-removed s))})

(def ^:dynamic *code-cache*
  "Optional atom for render-highlighted's growing-code cache, bound by the
   markdown render pass. Entry: {:text prefix :styled ansi :lang lang}."
  nil)

(defn- highlight-token
  "Style one [scope text] token; a token spanning newlines is wrapped per
   line-fragment so every line is self-contained (no color bleed)."
  [fmts [scope s]]
  (if-let [f (and scope (get fmts scope))]
    (->> (str/split s #"\n" -1)
         (map (fn [frag] (if (str/blank? frag) "" (f frag))))
         (str/join "\n"))
    s))

(defn- last-line-start-token
  "Index and start offset of the last token in TOKENS that begins a line of
   TEXT (at the start or just after a newline), or nil. Cutting there keeps
   the newline token on the cached side, so a word's :function/:attr
   lookahead (which skips spaces and tabs only) cannot reach into the tail
   and be decided differently on the next delta."
  [text tokens]
  (loop [i 0, off 0, found nil, ts (seq tokens)]
    (if-let [tok (first ts)]
      (let [found (if (or (zero? off) (= \newline (nth text (dec off))))
                    [i off]
                    found)]
        (recur (inc i) (+ off (count (second tok))) found (next ts)))
      found)))

(defn render-highlighted
  "Highlight CODE as LANG → vector of ANSI-colored lines (pi: highlightCode).
   Unsupported language → each line in mdCodeBlock color (pi behavior);
   unknown scopes pass through unstyled. Blank code renders no lines,
   matching the un-highlighted path. Public: shared by the markdown
   code-block renderer and the write tool renderer (pi: both call
   highlightCode).

   With CACHE (an atom) and a growing code text, the styled prefix through
   the last line-start token is reused and only the lines after it are
   tokenized — a token boundary is a clean scanner restart point for the
   generic scanner, and the newline before the cut keeps a word's lookahead
   out of the tail. A streaming code fence therefore re-highlights its last
   lines, not its whole body. The cache is keyed by language and raw prefix;
   markup/diff/Dockerfile/Markdown (custom scanners with construct state) are
   not prefix-cached."
  ([t code lang] (render-highlighted t code lang *code-cache*))
  ([t code lang cache]
   (if (str/blank? code)
     []
     (let [cacheable? (and cache (hl/token-boundaries-clean? lang))
           cached (when cacheable? @cache)
           prefix (when (and cached
                             (= lang (:lang cached))
                             (str/starts-with? code (:text cached))
                             (< (count (:text cached)) (count code)))
                    cached)
           suffix (if prefix (subs code (count (:text prefix))) code)
           tokens (hl/tokenize lang suffix)]
       (if tokens
         (let [fmts (highlight-formatters t)
               styled (mapv #(highlight-token fmts %) tokens)
               full (str (when prefix (:styled prefix)) (apply str styled))]
           (when cacheable?
             ;; Cut at the last line-start token: the next delta re-tokenizes
             ;; only the lines after it.
             (let [old-text (or (:text prefix) "")
                   old-styled (or (:styled prefix) "")
                   [cut-index cut-offset] (last-line-start-token suffix tokens)
                   advance? (pos? cut-offset)]
               (reset! cache
                       {:text (if advance? (str old-text (subs suffix 0 cut-offset)) old-text)
                        :styled (if advance?
                                  (str old-styled (apply str (subvec styled 0 cut-index)))
                                  old-styled)
                        :lang lang})))
           (str/split full #"\n" -1))
         (mapv (fn [l] (fg t :md-code-block l)) (str/split code #"\n" -1)))))))

(defn get-markdown-theme
  "pi-equivalent: getMarkdownTheme() — returns MarkdownTheme fn map."
  [t]
  {:heading (fn [s] (fg t :md-heading s))
   :link (fn [s] (fg t :md-link s))
   :link-url (fn [s] (fg t :md-link-url s))
   :code (fn [s] (fg t :md-code s))
   :code-block (fn [s] (fg t :md-code-block s))
   :code-block-border (fn [s] (fg t :md-code-block-border s))
   :quote (fn [s] (fg t :md-quote s))
   :quote-border (fn [s] (fg t :md-quote-border s))
   :hr (fn [s] (fg t :md-hr s))
   :list-bullet (fn [s] (fg t :md-list-bullet s))
   :highlight-code (fn [code lang] (render-highlighted t code lang *code-cache*))
   :bold bold
   :italic italic
   :underline underline
   :strikethrough strikethrough})

(defn get-select-list-theme
  "pi-equivalent: getSelectListTheme() — returns SelectListTheme fn map."
  [t]
  {:selected-prefix (fn [s] (fg t :accent s))
   :selected-text (fn [s] (fg t :accent s))
   :description (fn [s] (fg t :muted s))
   :scroll-info (fn [s] (fg t :muted s))
   :no-match (fn [s] (fg t :muted s))})

(defn get-editor-theme
  "pi-equivalent: getEditorTheme() — returns EditorTheme map."
  [t]
  {:border-color (fn [s] (fg t :border-muted s))
   :select-list (get-select-list-theme t)})

(defn get-settings-list-theme
  "pi-equivalent: getSettingsListTheme() — returns SettingsListTheme fn map."
  [t]
  {:label (fn [s sel] (if sel (fg t :accent s) s))
   :value (fn [s sel] (if sel (fg t :accent s) (fg t :muted s)))
   :description (fn [s] (fg t :dim s))
   :cursor (fg t :accent "→ ")
   :hint (fn [s] (fg t :dim s))})

;; ═══════════════════════════════════════════════════════════════════════════
;; Theme construction — from pi-identical EDN schema
;; ═══════════════════════════════════════════════════════════════════════════
;;
;; EDN file format (identical structure and color values to pi's JSON):
;;
;;   {:name "dark"
;;    :vars {"text" "okhsl(234 3% 89%)" "blue" "okhsl(232 54% 67%)" ...}
;;    :colors {"accent" "violet" "border" "blue" ...}}
;;
;; Color values: "#rgb"/"#rrggbb", "okhsl(H S% L%)", "oklch(L C H)", a
;; 0-255 palette index, a :vars name (references chain), or "" for the
;; terminal default.
;;
;; or flat format (backward compat):
;;
;;   {:accent "#8abeb7" :border "#5f87ff" ...}
;;
;; ═══════════════════════════════════════════════════════════════════════════

(defn- camel->kebab
  "Convert camelCase string to kebab-case keyword."
  [s]
  (keyword
   (str/lower-case
    (str/replace s #"([a-z])([A-Z])" "$1-$2"))))

;; Mapping from pi's camelCase color keys to kebab-case keywords
(def token-map
  {"accent" :accent "border" :border
   "borderAccent" :border-accent "borderMuted" :border-muted
   "success" :success "error" :error "warning" :warning
   "muted" :muted "dim" :dim "text" :text
   "thinkingText" :thinking-text
   "scrollbarTrack" :scrollbar-track "scrollbarThumb" :scrollbar-thumb
   "searchMatchText" :search-match-text
   "selectedBg" :selected-bg "searchMatchBg" :search-match-bg
   "userMessageBg" :user-message-bg "userMessageText" :user-message-text
   "customMessageBg" :custom-message-bg "customMessageText" :custom-message-text
   "customMessageLabel" :custom-message-label
   "toolPendingBg" :tool-pending-bg "toolSuccessBg" :tool-success-bg
   "toolErrorBg" :tool-error-bg
   "toolTitle" :tool-title "toolOutput" :tool-output
   "mdHeading" :md-heading "mdLink" :md-link "mdLinkUrl" :md-link-url
   "mdCode" :md-code "mdCodeBlock" :md-code-block
   "mdCodeBlockBorder" :md-code-block-border
   "mdQuote" :md-quote "mdQuoteBorder" :md-quote-border
   "mdHr" :md-hr "mdListBullet" :md-list-bullet
   "toolDiffAdded" :tool-diff-added "toolDiffRemoved" :tool-diff-removed
   "toolDiffContext" :tool-diff-context
   "syntaxComment" :syntax-comment "syntaxKeyword" :syntax-keyword
   "syntaxFunction" :syntax-function "syntaxVariable" :syntax-variable
   "syntaxString" :syntax-string "syntaxNumber" :syntax-number
   "syntaxType" :syntax-type "syntaxOperator" :syntax-operator
   "syntaxPunctuation" :syntax-punctuation
   "thinkingOff" :thinking-off "thinkingMinimal" :thinking-minimal
   "thinkingLow" :thinking-low "thinkingMedium" :thinking-medium
   "thinkingHigh" :thinking-high "thinkingXhigh" :thinking-xhigh
   "thinkingMax" :thinking-max
   "bashMode" :bash-mode})

(defn- normalize-color-keys
  "Normalize a colors map to kebab-case keyword keys (accepts pi camelCase
   string keys and kmet kebab keywords)."
  [raw]
  (into {} (map (fn [[k v]]
                  [(if (keyword? k) k (camel->kebab k)) v]))
        raw))

(defn- ok-color-string?
  "True for strings pi's resolveVarRefs treats as literal colors."
  [v]
  (or (str/starts-with? v "#")
      (str/starts-with? (str/lower-case v) "okhsl(")
      (str/starts-with? (str/lower-case v) "oklch(")))

(defn- resolve-var-refs
  "Resolve chained variable references like pi's resolveVarRefs: literal
   colors, the terminal default and non-strings pass through; a missing or
   circular reference throws."
  [value vars]
  (letfn [(walk [v visited]
            (cond
              (not (string? v)) v
              (or (= v "") (ok-color-string? v)) v
              (contains? visited v)
              (throw (ex-info (str "Circular variable reference detected: " v)
                              {:type :theme-validation :variable v}))
              (contains? vars v) (walk (get vars v) (conj visited v))
              :else (throw (ex-info (str "Variable reference not found: " v)
                                    {:type :theme-validation :variable v}))))]
    (walk value #{})))

(defn- apply-optional-color-fallbacks
  "Fill pi's optional tokens from the theme's own colors before resolution
   (pi: withThemeColorFallbacks): scrollbarTrack inherits muted, scrollbarThumb
   inherits text, searchMatchBg inherits selectedBg, searchMatchText inherits
   text, thinkingMax inherits thinkingXhigh. Missing fallback sources stay
   absent so make-theme can fill them from the dark palette."
  [raw]
  (cond-> raw
    (not (contains? raw :thinking-max)) (assoc :thinking-max (get raw :thinking-xhigh))
    (not (contains? raw :scrollbar-track)) (assoc :scrollbar-track (get raw :muted))
    (not (contains? raw :scrollbar-thumb)) (assoc :scrollbar-thumb (get raw :text))
    (not (contains? raw :search-match-bg)) (assoc :search-match-bg (get raw :selected-bg))
    (not (contains? raw :search-match-text)) (assoc :search-match-text (get raw :text))))

(def ^:private dark-theme-data
  "pi's built-in dark.json, in EDN: OKHSL colors with shared vars, including
   the scrollbar and search-match tokens."
  {:name "dark"
   :vars {"text" "okhsl(234 3% 89%)"
          "muted" "okhsl(229 6% 67%)"
          "violet" "okhsl(295 50% 67%)"
          "blue" "okhsl(232 54% 67%)"
          "green" "okhsl(159 59% 67%)"
          "red" "okhsl(20 72% 67%)"
          "yellow" "okhsl(83 88% 67%)"
          "blueBg" "okhsl(233 41% 24%)"}
   :colors {"accent" "violet"
            "border" "okhsl(231 57% 65%)"
            "borderAccent" "okhsl(295 53% 64%)"
            "borderMuted" "okhsl(229 8% 53%)"
            "success" "green"
            "error" "red"
            "warning" "yellow"
            "muted" "muted"
            "dim" "okhsl(229 8% 56%)"
            "text" "text"
            "thinkingText" "okhsl(226 7% 65%)"
            "selectedBg" "blueBg"
            "scrollbarTrack" "okhsl(237 7% 33%)"
            "scrollbarThumb" "okhsl(232 7% 65%)"
            "searchMatchBg" "okhsl(53 51% 24%)"
            "searchMatchText" "muted"
            "userMessageBg" "blueBg"
            "userMessageText" "text"
            "customMessageBg" "okhsl(295 42% 24%)"
            "customMessageText" "muted"
            "customMessageLabel" "violet"
            "toolPendingBg" "okhsl(229 5% 24%)"
            "toolSuccessBg" "okhsl(158 46% 25%)"
            "toolErrorBg" "okhsl(19 54% 25%)"
            "toolTitle" "text"
            "toolOutput" "muted"
            "mdHeading" "yellow"
            "mdLink" "blue"
            "mdLinkUrl" "muted"
            "mdCode" "violet"
            "mdCodeBlock" "green"
            "mdCodeBlockBorder" "muted"
            "mdQuote" "muted"
            "mdQuoteBorder" "muted"
            "mdHr" "muted"
            "mdListBullet" "violet"
            "toolDiffAdded" "green"
            "toolDiffRemoved" "red"
            "toolDiffContext" "muted"
            "syntaxComment" "muted"
            "syntaxKeyword" "blue"
            "syntaxFunction" "yellow"
            "syntaxVariable" "okhsl(202 58% 67%)"
            "syntaxString" "okhsl(52 67% 67%)"
            "syntaxNumber" "green"
            "syntaxType" "violet"
            "syntaxOperator" "muted"
            "syntaxPunctuation" "muted"
            "thinkingOff" "okhsl(229 8% 49%)"
            "thinkingMinimal" "okhsl(232 20% 52%)"
            "thinkingLow" "okhsl(232 45% 54%)"
            "thinkingMedium" "okhsl(263 59% 56%)"
            "thinkingHigh" "okhsl(295 73% 59%)"
            "thinkingXhigh" "okhsl(337 81% 61%)"
            "thinkingMax" "okhsl(20 99% 63%)"
            "bashMode" "okhsl(159 64% 65%)"}})

(def ^:private light-theme-data
  "pi's built-in light.json, in EDN: OKHSL colors with shared vars."
  {:name "light"
   :vars {"text" "okhsl(225 5% 27%)"
          "muted" "okhsl(229 8% 47%)"
          "violet" "okhsl(295 60% 46%)"
          "blue" "okhsl(231 68% 47%)"
          "green" "okhsl(159 75% 46%)"
          "red" "okhsl(20 91% 47%)"
          "yellow" "okhsl(83 99% 47%)"
          "blueBg" "okhsl(235 19% 91%)"}
   :colors {"accent" "violet"
            "border" "okhsl(231 67% 55%)"
            "borderAccent" "okhsl(295 59% 55%)"
            "borderMuted" "okhsl(235 7% 66%)"
            "success" "green"
            "error" "red"
            "warning" "yellow"
            "muted" "muted"
            "dim" "okhsl(229 7% 59%)"
            "text" "text"
            "thinkingText" "okhsl(234 8% 55%)"
            "selectedBg" "blueBg"
            "scrollbarTrack" "okhsl(248 3% 90%)"
            "scrollbarThumb" "okhsl(226 7% 65%)"
            "searchMatchBg" "okhsl(56 22% 91%)"
            "searchMatchText" "muted"
            "userMessageBg" "blueBg"
            "userMessageText" "text"
            "customMessageBg" "okhsl(295 25% 91%)"
            "customMessageText" "muted"
            "customMessageLabel" "violet"
            "toolPendingBg" "okhsl(248 3% 91%)"
            "toolSuccessBg" "okhsl(156 21% 91%)"
            "toolErrorBg" "okhsl(24 23% 91%)"
            "toolTitle" "text"
            "toolOutput" "muted"
            "mdHeading" "yellow"
            "mdLink" "blue"
            "mdLinkUrl" "muted"
            "mdCode" "violet"
            "mdCodeBlock" "green"
            "mdCodeBlockBorder" "muted"
            "mdQuote" "muted"
            "mdQuoteBorder" "muted"
            "mdHr" "muted"
            "mdListBullet" "violet"
            "toolDiffAdded" "green"
            "toolDiffRemoved" "red"
            "toolDiffContext" "muted"
            "syntaxComment" "muted"
            "syntaxKeyword" "blue"
            "syntaxFunction" "yellow"
            "syntaxVariable" "okhsl(203 73% 46%)"
            "syntaxString" "okhsl(52 84% 46%)"
            "syntaxNumber" "green"
            "syntaxType" "violet"
            "syntaxOperator" "muted"
            "syntaxPunctuation" "muted"
            "thinkingOff" "okhsl(223 5% 80%)"
            "thinkingMinimal" "okhsl(229 14% 78%)"
            "thinkingLow" "okhsl(232 33% 76%)"
            "thinkingMedium" "okhsl(264 48% 74%)"
            "thinkingHigh" "okhsl(295 62% 72%)"
            "thinkingXhigh" "okhsl(337 74% 70%)"
            "thinkingMax" "okhsl(20 98% 68%)"
            "bashMode" "okhsl(159 74% 55%)"}})

(defn- is-pi-schema?
  "Check if data uses pi's {:name :vars :colors} schema."
  [data]
  (contains? data :colors))

(defn- resolve-colors-from-pi-schema
  "Resolve colors from pi-style {:name :vars :colors} data: optional tokens
   fall back to the theme's own colors, then var references resolve (chained,
   like pi). :colors keys can be camelCase strings (pi JSON style) or
   kebab-case keywords."
  [data color-mode]
  (let [vars (get data :vars {})
        raw (apply-optional-color-fallbacks (normalize-color-keys (:colors data {})))
        get-color (fn [k] (when-let [v (get raw k)] (resolve-var-refs v vars)))
        fg-map (into {} (keep (fn [k] (when-let [v (get-color k)] [k (fg-ansi v color-mode)])) FG-TOKENS))
        bg-map (into {} (keep (fn [k] (when-let [v (get-color k)] [k (bg-ansi v color-mode)])) BG-TOKENS))]
    {:fg-map fg-map :bg-map bg-map}))

(defn- resolve-colors-from-flat-map
  "Resolve colors from a flat kebab-case keyword → value map (legacy format)."
  [data color-mode]
  (let [data (apply-optional-color-fallbacks data)
        fg-map (into {} (keep (fn [k] (when-let [v (get data k)] [k (fg-ansi v color-mode)])) FG-TOKENS))
        bg-map (into {} (keep (fn [k] (when-let [v (get data k)] [k (bg-ansi v color-mode)])) BG-TOKENS))]
    {:fg-map fg-map :bg-map bg-map}))

(defn- detect-color-mode
  "The terminal's color capability as a theme :color-mode (pi: getColorMode /
   getCapabilities().trueColor). True-color terminals get :truecolor;
   everything else :256color — the safe default, since truecolor codes
   degrade to the default bg on unsupported terminals, which breaks light
   themes where dark text lands on a dark fallback. Capabilities come from
   the shared kmet.tui.terminal-image detection (COLORTERM plus the known
   terminal programs: kitty, wezterm, ghostty, iTerm, Warp, Windows
   Terminal, VS Code, alacritty), cached once per process."
  []
  (if (:true-color (term-image/get-capabilities)) :truecolor :256color))

(defn make-theme
  "Create a Theme from an EDN color map.
   Accepts pi-schema {:name \"...\" :vars {...} :colors {...}}
   or flat schema {:accent \"...\" :border \"...\" ...}.
   Missing tokens fall back to pi's dark palette (dark-theme-data). MODE pins
   the color mode (:truecolor/:256color); nil detects it from the terminal
   env (pi: createTheme's mode argument, defaulting to
   getCapabilities().trueColor)."
  ([data] (make-theme data nil nil))
  ([data source-path] (make-theme data source-path nil))
  ([data source-path mode]
   (let [color-mode (or mode (detect-color-mode))
         name (or (:name data) "unnamed")
         {:keys [fg-map bg-map]} (if (is-pi-schema? data)
                                   (resolve-colors-from-pi-schema data color-mode)
                                   (resolve-colors-from-flat-map data color-mode))
         ;; Fill missing tokens from the dark palette; the optional tokens
         ;; already fell back to this theme's own colors in the resolvers.
         dark (resolve-colors-from-pi-schema dark-theme-data color-mode)]
     (map->Theme
      {:name name
       :fg-colors (merge (:fg-map dark) fg-map)
       :bg-colors (merge (:bg-map dark) bg-map)
       :color-mode color-mode
       :source-path source-path}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Built-in themes — pi's dark.json / light.json palettes (OKHSL colors),
;; built through the same resolver as loaded theme files.
;; ═══════════════════════════════════════════════════════════════════════════

(def dark-theme (make-theme dark-theme-data))

(def light-theme (make-theme light-theme-data))

;; ═══════════════════════════════════════════════════════════════════════════
;; Registry & Loading — EDN only, same schema as pi's JSON
(declare load-theme-from-path get-default-theme)
;; ═══════════════════════════════════════════════════════════════════════════

(defonce ^:private themes (atom {"dark" dark-theme "light" light-theme}))

(defn register-theme!
  "Register a theme by name."
  [theme]
  (swap! themes assoc (:name theme) theme))

(defn unregister-theme!
  "Remove the theme NAME from the registry (extension unload; built-ins
   should never be unregistered). No-op when absent. With THEME, remove it
   only while NAME still holds that value — the guard that keeps one
   contributor's teardown from dropping another's later registration, the
   same rule a registry disposer follows."
  ([theme-name]
   (swap! themes dissoc (str theme-name))
   nil)
  ([theme-name theme]
   (swap! themes (fn [ts]
                   (let [k (str theme-name)]
                     (if (identical? theme (get ts k)) (dissoc ts k) ts))))
   nil))

(defn get-theme
  "Get a theme by name. Falls back to 'dark'."
  [name]
  (or (get @themes (str name))
      (get @themes "dark")
      dark-theme))

(defn get-theme-by-name
  "Get a theme by name, or nil when no such theme is registered
   (pi: getThemeByName — undefined for unknown names)."
  [name]
  (get @themes (str name)))

(defn get-all-themes
  "All registered themes as a name → theme map (pi: getAvailableThemesWithPaths
   without the file paths — those come from the loaders)."
  []
  @themes)

(defn load-theme-file!
  "Load and register a theme from an EDN file at PATH, returning the theme (or
   nil when it failed to load — warnings print to stderr, pi:
   loadThemeFromPath + register — the package-resource theme load unit)."
  [path]
  (try
    (let [theme (load-theme-from-path path)]
      (register-theme! theme)
      theme)
    (catch Exception e
      (binding [*out* *err*]
        (println "Warning: Failed to load theme" (fs/file-name path) ":" (ex-message e)))
      nil)))

(defn theme-files-in-dir
  "Theme .edn files directly inside DIR. Returns the ordered file paths."
  [dir]
  (when (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %)
                       (str/ends-with? (fs/file-name %) ".edn")))
         (map str)
         vec)))

(defn load-theme-paths!
  "Load and register themes from explicit .edn file paths (the package-
   resource unit), returning the themes that loaded. Same warning behavior as
   load-theme-file!. Registration is by name, so re-reading a path replaces
   the theme it names; nothing here removes a theme whose file left the list —
   the callers re-read their configured paths (there is no clear-all), and an
   extension-contributed theme is removed by its owner's teardown."
  [paths]
  (into [] (keep load-theme-file!) paths))

(defn load-themes-from-dir
  "Load all .edn theme files from a directory, returning the themes that
   loaded."
  [dir]
  (load-theme-paths! (theme-files-in-dir dir)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Validation (pi: parseThemeJson — required color tokens + name check)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private optional-color-tokens
  "Tokens not required by pi's schema (Type.Optional)."
  #{:thinking-max :scrollbar-track :scrollbar-thumb
    :search-match-bg :search-match-text})

(defn- missing-color-tokens
  "Required color tokens missing from the theme's :colors map (pi: required
   schema properties, minus :thinking-max)."
  [data]
  (let [raw (if (is-pi-schema? data) (:colors data {}) data)
        keys (set (keys (normalize-color-keys raw)))]
    (->> (concat FG-TOKENS BG-TOKENS)
         (remove optional-color-tokens)
         (remove keys)
         (sort-by str)
         vec)))

(defn- validate-theme-data!
  "Throw when the theme data misses required color tokens or has an invalid
   name (pi: parseThemeJson). The error lists the sorted missing tokens."
  [data]
  (let [missing (missing-color-tokens data)]
    (when (seq missing)
      (throw (ex-info (str "Missing required color tokens:\n"
                           (apply str (map #(str "  - " (name %) "\n") missing))
                           "\nPlease add these colors to your theme's :colors map.\n"
                           "See the built-in themes (dark, light) for reference values.")
                      {:type :theme-validation :missing missing}))))
  (when (and (string? (:name data)) (str/includes? (:name data) "/"))
    (throw (ex-info (str "Invalid theme name \"" (:name data)
                         "\": theme names cannot contain \"/\" because it is reserved "
                         "for automatic light/dark theme settings.")
                    {:type :theme-validation}))))

(defn load-theme-from-path
  "Load a theme from an EDN file at PATH; throws on parse errors or missing
   required color tokens (pi: loadThemeFromPath)."
  [path]
  (let [data (edn/read-string (slurp path))]
    (validate-theme-data! data)
    (let [basename (str/replace (fs/file-name path) #"\.edn$" "")]
      (if (string? (:name data))
        (make-theme data path)
        (make-theme (assoc data :name basename) path)))))

;; ═══════════════════════════════════════════════════════════════════════════

;; ─── Current theme state (pi: global Theme instance + setTheme machinery) ───
;; The active theme as a reactive input (tui.md §9): components
;; subscribe through kmet.ui.tui.subs/theme-sub (= this atom) instead of
;; receiving the theme as a constructor argument; a palette switch
;; invalidates exactly the subscribed subtrees. Plain get-current-theme
;; reads remain valid for construction-time snapshots.
(defonce theme-atom (atom dark-theme))
(defonce ^:private current-theme-name (atom nil))
(defonce ^:private theme-change-callback (atom nil))

(defn- notify-theme-change!
  []
  (when-let [f @theme-change-callback]
    (try (f) (catch Exception _))))

;; ─── Custom-theme file watcher (pi: startThemeWatcher) ─────────────────────

(defonce ^:private custom-themes-dir (atom nil))
(defonce ^:private theme-watcher (atom nil))
(defonce ^:private theme-watch-mtime (atom nil))

(defn set-custom-themes-dir!
  "Set the custom themes directory (the agent-dir themes auto root) used
   by the watcher."
  [dir]
  (reset! custom-themes-dir dir))

(defn- get-custom-themes-dir [] @custom-themes-dir)

(defn stop-theme-watcher!
  []
  (when-let [f @theme-watcher]
    (future-cancel f)
    (reset! theme-watcher nil)))

(defn start-theme-watcher!
  "Poll the current theme's file in DIR every second and reload on change
   (pi: startThemeWatcher — a polling equivalent: babashka.fs has no watcher,
   and java.nio is out per AGENTS.md). Keeps the last good theme on parse
   errors and notifies the change callback after reload. No-op when DIR is
   nil or the theme file doesn't exist."
  [dir]
  (stop-theme-watcher!)
  (let [name @current-theme-name]
    (when (and dir name (not (contains? #{"dark" "light" "<in-memory>"} name)))
      (let [f (io/file dir (str name ".edn"))]
        (when (fs/exists? f)
          (reset! theme-watch-mtime (fs/last-modified-time f))
          (reset! theme-watcher
                  (future
                    (try
                      (loop []
                        (Thread/sleep 1000)
                        (when (and (= name @current-theme-name)
                                   (fs/exists? f))
                          (let [m (fs/last-modified-time f)]
                            (when (not= m @theme-watch-mtime)
                              (reset! theme-watch-mtime m)
                              (try
                                (let [t (load-theme-from-path (str f))]
                                  (register-theme! t)
                                  (reset! theme-atom t)
                                  (notify-theme-change!))
                                (catch Exception _ "keep the last good theme")))))
                        (recur))
                      (catch InterruptedException _)))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Terminal theme detection (pi: theme.ts detection section)
;; ═══════════════════════════════════════════════════════════════════════════

(defn get-current-theme
  "The active theme instance (pi: the global theme getter)."
  []
  @theme-atom)

(defn get-current-theme-name
  "The active theme's name (pi: currentThemeName)."
  []
  @current-theme-name)

(defn on-theme-change
  "Register the callback invoked after every applied theme change (pi:
   onThemeChange — the app uses it to invalidate + re-theme components)."
  [f]
  (reset! theme-change-callback f))

(defn init-theme!
  "Set the current theme to NAME (default: environment detection), falling
   back to dark when unknown (pi: initTheme). ENABLE-WATCHER? starts the
   custom-theme file watcher."
  ([name] (init-theme! name false))
  ([name enable-watcher?]
   (let [name (or name (get-default-theme))]
     (reset! current-theme-name name)
     (reset! theme-atom (get-theme name))
     (when enable-watcher? (start-theme-watcher! (get-custom-themes-dir))))))

(defn set-theme!
  "Switch the current theme to NAME, falling back to dark on failure (pi:
   setTheme). Returns {:success bool :error msg?}."
  ([name] (set-theme! name false))
  ([name enable-watcher?]
   (let [t (get @themes (str name))]
     (if (nil? t)
       (do (reset! current-theme-name "dark")
           (reset! theme-atom dark-theme)
           {:success false :error (str "Theme not found: " name)})
       (do (reset! current-theme-name (str name))
           (reset! theme-atom t)
           (when enable-watcher? (start-theme-watcher! (get-custom-themes-dir)))
           (notify-theme-change!)
           {:success true})))))

(defn set-theme-instance!
  "Switch to an in-memory Theme instance; stops the file watcher (pi:
   setThemeInstance — can't watch a direct instance)."
  [t]
  (stop-theme-watcher!)
  (reset! current-theme-name "<in-memory>")
  (reset! theme-atom t)
  (notify-theme-change!))

;; ═══════════════════════════════════════════════════════════════════════════

(defn- ansi256->hex
  "ANSI 256-color index → hex string (pi: ansi256ToHex)."
  [index]
  (let [basic ["#000000" "#800000" "#008000" "#808000"
               "#000080" "#800080" "#008080" "#c0c0c0"
               "#808080" "#ff0000" "#00ff00" "#ffff00"
               "#0000ff" "#ff00ff" "#00ffff" "#ffffff"]]
    (cond
      (< index 16) (nth basic index)
      (< index 232)
      (let [cube-index (- index 16)
            r (quot cube-index 36)
            g (quot (mod cube-index 36) 6)
            b (mod cube-index 6)
            to-hex (fn [n] (format "%02x" (if (zero? n) 0 (+ 55 (* n 40)))))]
        (str "#" (to-hex r) (to-hex g) (to-hex b)))
      :else
      (let [gray (+ 8 (* (- index 232) 10))]
        (str "#" (format "%02x" gray) (format "%02x" gray) (format "%02x" gray))))))

(defn- get-rgb-luminance
  "Relative luminance of an RGB color (pi: getRgbColorLuminance)."
  [{:keys [r g b]}]
  (let [to-linear (fn [c]
                    (let [v (/ c 255)]
                      (if (<= v 0.03928)
                        (/ v 12.92)
                        (Math/pow (/ (+ v 0.055) 1.055) 2.4))))]
    (+ (* 0.2126 (to-linear r))
       (* 0.7152 (to-linear g))
       (* 0.0722 (to-linear b)))))

(defn get-theme-for-rgb-color
  ":light when the RGB's luminance >= 0.5, else :dark (pi:
   getThemeForRgbColor)."
  [rgb]
  (if (>= (get-rgb-luminance rgb) 0.5) :light :dark))

(defn detect-terminal-background-from-env
  "Detect the terminal theme from the COLORFGBG environment variable
   (background index → luminance); falls back to :dark with low confidence
   (pi: detectTerminalBackgroundFromEnv). Returns
   {:theme :dark|:light :source ... :detail ... :confidence :high|:low}."
  ([] (detect-terminal-background-from-env (System/getenv)))
  ([env]
   (let [colorfgbg (or (get env "COLORFGBG") "")]
     (if-let [bg (some (fn [part]
                         (let [n (parse-long (str/trim part))]
                           (when (and n (<= 0 n 255)) n)))
                       (reverse (str/split colorfgbg #";")))]
       {:theme (if (>= (get-rgb-luminance (hex->rgb (ansi256->hex bg))) 0.5)
                 :light :dark)
        :source "COLORFGBG"
        :detail (str "background color index " bg)
        :confidence :high}
       {:theme :dark
        :source "fallback"
        :detail "no terminal background hint found"
        :confidence :low}))))

(defn get-default-theme
  "The default theme name from environment detection (pi: getDefaultTheme)."
  []
  (name (:theme (detect-terminal-background-from-env))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Auto light/dark setting (pi: parseAutoThemeSetting / resolveThemeSetting)
;; ═══════════════════════════════════════════════════════════════════════════

(defn parse-auto-theme-setting
  "Parse an auto theme setting \"light-theme/dark-theme\" (exactly one
   slash) into {:light-theme ... :dark-theme ...} or nil (pi:
   parseAutoThemeSetting)."
  [setting]
  (when (and setting (string? setting))
    (let [slash (str/index-of setting "/")]
      (when (and slash (not (str/includes? (subs setting (inc slash)) "/")))
        (let [light (str/trim (subs setting 0 slash))
              dark (str/trim (subs setting (inc slash)))]
          (when (and (seq light) (seq dark))
            {:light-theme light :dark-theme dark}))))))

(defn resolve-theme-setting
  "Resolve a theme setting against the terminal theme: auto settings
   (\"light/dark\") pick by terminal theme; plain names pass through;
   anything with '/' that is not a valid auto setting → nil (pi:
   resolveThemeSetting)."
  [setting terminal-theme]
  (if-let [auto (parse-auto-theme-setting setting)]
    (if (= terminal-theme :light) (:light-theme auto) (:dark-theme auto))
    (when (and (string? setting) (not (str/includes? setting "/")))
      setting)))
