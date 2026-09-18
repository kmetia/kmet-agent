(ns kmet.tui.theme
  "Theme system — identical structure to pi's theme.ts.
   EDN-only loading with same :vars/:colors schema as pi's JSON."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.libs.highlight :as hl]
            [kmet.libs.terminal-image :as term-image]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Color tokens — matching pi's ThemeColor / ThemeBg exactly
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:const FG-TOKENS
  [:accent :border :border-accent :border-muted
   :success :error :warning :muted :dim :text :thinking-text
   :user-message-text :custom-message-text :custom-message-label
   :tool-title :tool-output
   :md-heading :md-link :md-link-url :md-code
   :md-code-block :md-code-block-border :md-quote :md-quote-border
   :md-hr :md-list-bullet :md-table-border
   :tool-diff-added :tool-diff-removed :tool-diff-context
   :syntax-comment :syntax-keyword :syntax-function
   :syntax-variable :syntax-string :syntax-number
   :syntax-type :syntax-operator :syntax-punctuation
   :thinking-off :thinking-minimal :thinking-low
   :thinking-medium :thinking-high :thinking-xhigh :thinking-max
   :bash-mode])

(def ^:const BG-TOKENS
  [:selected-bg :user-message-bg :custom-message-bg
   :tool-pending-bg :tool-success-bg :tool-error-bg
   :scrollbar-thumb])

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
;; Color helpers — matching pi's hexToRgb, rgbTo256, fgAnsi, bgAnsi
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private FG-RST "\u001b[39m")
(def ^:private BG-RST "\u001b[49m")

(defn hex->rgb [hex]
  (let [h (subs hex 1)
        r (Integer/parseInt (subs h 0 2) 16)
        g (Integer/parseInt (subs h 2 4) 16)
        b (Integer/parseInt (subs h 4 6) 16)]
    {:r r :g g :b b}))

(defn- rgb->256 [r g b]
  (let [cube [0 95 135 175 215 255]
        closest #(first (sort-by (fn [x] (Math/abs (- % x))) cube))
        ri (.indexOf cube (closest r))
        gi (.indexOf cube (closest g))
        bi (.indexOf cube (closest b))
        cube-idx (+ 16 (* 36 ri) (* 6 gi) bi)
        ;; grayscale check
        maxc (max r g b)
        minc (min r g b)
        spread (- maxc minc)
        gray (int (+ (* 0.299 r) (* 0.587 g) (* 0.114 b)))]
    (if (< spread 10)
      (let [gray-idx (int (Math/round (/ (double (- gray 8)) 10)))]
        (+ 232 (min 23 (max 0 gray-idx))))
      cube-idx)))

(defn- fg-ansi [value color-mode]
  (cond
    (nil? value) FG-RST
    (= value "") FG-RST
    (number? value) (str "\u001b[38;5;" value "m")
    (string? value)
    (if (str/starts-with? value "#")
      (let [{:keys [r g b]} (hex->rgb value)]
        (if (= color-mode :truecolor)
          (str "\u001b[38;2;" r ";" g ";" b "m")
          (str "\u001b[38;5;" (rgb->256 r g b) "m")))
      FG-RST)
    :else FG-RST))

(defn- bg-ansi [value color-mode]
  (cond
    (nil? value) BG-RST
    (= value "") BG-RST
    (number? value) (str "\u001b[48;5;" value "m")
    (string? value)
    (if (str/starts-with? value "#")
      (let [{:keys [r g b]} (hex->rgb value)]
        (if (= color-mode :truecolor)
          (str "\u001b[48;2;" r ";" g ";" b "m")
          (str "\u001b[48;5;" (rgb->256 r g b) "m")))
      BG-RST)
    :else BG-RST))

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

(defn render-highlighted
  "Highlight CODE as LANG → vector of ANSI-colored lines (pi: highlightCode).
   Unsupported language → each line in mdCodeBlock color (pi behavior);
   unknown scopes pass through unstyled. Tokens spanning newlines are wrapped
   per line-fragment so every line is self-contained (no color bleed). Blank
   code renders no lines, matching the un-highlighted path. Public: shared by
   the markdown code-block renderer and the write tool renderer (pi: both
   call highlightCode)."
  [t code lang]
  (if (str/blank? code)
    []
    (if-let [tokens (hl/tokenize lang code)]
      (let [fmts (highlight-formatters t)
            styled (fn [[scope s]]
                     (if-let [f (and scope (get fmts scope))]
                       (->> (str/split s #"\n" -1)
                            (map (fn [frag] (if (str/blank? frag) "" (f frag))))
                            (str/join "\n"))
                       s))]
        (str/split (apply str (map styled tokens)) #"\n" -1))
      (mapv (fn [l] (fg t :md-code-block l)) (str/split code #"\n" -1)))))

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
   :table-border (fn [s] (fg t :md-table-border s))
   :highlight-code (fn [code lang] (render-highlighted t code lang))
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
;; EDN file format (identical structure to pi's JSON):
;;
;;   {:name "dark"
;;    :vars {"cyan" "#00d7ff" "blue" "#5f87ff" ...}
;;    :colors {:accent "accent" :border "blue" ...}}
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
   "selectedBg" :selected-bg
   "scrollbarThumb" :scrollbar-thumb
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
   "mdTableBorder" :md-table-border
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

(defn- is-pi-schema?
  "Check if data uses pi's {:name :vars :colors} schema."
  [data]
  (contains? data :colors))

(defn- resolve-colors-from-pi-schema
  "Resolve colors from pi-style {:name :vars :colors} data.
   :colors keys can be camelCase strings (pi JSON style) or kebab-case keywords."
  [data color-mode]
  (let [vars (get data :vars {})
        raw (normalize-color-keys (:colors data {}))
        ;; pi: thinkingMax ?? thinkingXhigh (Type.Optional in the schema)
        raw (if (contains? raw :thinking-max)
              raw
              (assoc raw :thinking-max (get raw :thinking-xhigh)))
        resolve (fn [v]
                  (cond
                    (number? v) v
                    (string? v)
                    (if (str/starts-with? v "#")
                      v
                      (let [var-val (get vars v)]
                        (if var-val var-val v)))
                    :else nil))
        get-color (fn [k] (when-let [v (get raw k)] (resolve v)))
        fg-map (into {} (keep (fn [k] (when-let [v (get-color k)] [k (fg-ansi v color-mode)])) FG-TOKENS))
        bg-map (into {} (keep (fn [k] (when-let [v (get-color k)] [k (bg-ansi v color-mode)])) BG-TOKENS))]
    {:fg-map fg-map :bg-map bg-map}))

(defn- resolve-colors-from-flat-map
  "Resolve colors from a flat kebab-case keyword → value map (legacy format)."
  [data color-mode]
  (let [data (if (contains? data :thinking-max)
               data
               (assoc data :thinking-max (get data :thinking-xhigh)))
        fg-map (into {} (keep (fn [k] (when-let [v (get data k)] [k (fg-ansi v color-mode)])) FG-TOKENS))
        bg-map (into {} (keep (fn [k] (when-let [v (get data k)] [k (bg-ansi v color-mode)])) BG-TOKENS))]
    {:fg-map fg-map :bg-map bg-map}))

(defn- detect-color-mode
  "The terminal's color capability as a theme :color-mode (pi: getColorMode /
   getCapabilities().trueColor). True-color terminals get :truecolor;
   everything else :256color — the safe default, since truecolor codes
   degrade to the default bg on unsupported terminals, which breaks light
   themes where dark text lands on a dark fallback. Capabilities come from
   the shared kmet.libs.terminal-image detection (COLORTERM plus the known
   terminal programs: kitty, wezterm, ghostty, iTerm, Warp, Windows
   Terminal, VS Code, alacritty), cached once per process."
  []
  (if (:true-color (term-image/get-capabilities)) :truecolor :256color))

(defn make-theme
  "Create a Theme from an EDN color map.
   Accepts pi-schema {:name \"...\" :vars {...} :colors {...}}
   or flat schema {:accent \"...\" :border \"...\" ...}.
   Falls back to dark theme keys for missing colors. MODE pins the color
   mode (:truecolor/:256color); nil detects it from the terminal env (pi:
   createTheme's mode argument, defaulting to getCapabilities().trueColor)."
  ([data] (make-theme data nil nil))
  ([data source-path] (make-theme data source-path nil))
  ([data source-path mode]
   (let [color-mode (or mode (detect-color-mode))
         name (or (:name data) "unnamed")
         {:keys [fg-map bg-map]} (if (is-pi-schema? data)
                                   (resolve-colors-from-pi-schema data color-mode)
                                   (resolve-colors-from-flat-map data color-mode))
         ;; Fill missing tokens with dark theme defaults
         dark (resolve-colors-from-flat-map
               {:accent "#8abeb7" :border "#5f87ff" :border-accent "#00d7ff"
                :border-muted "#505050" :success "#b5bd68" :error "#cc6666"
                :warning "#ffff00" :muted "#808080" :dim "#666666"
                :text "#d4d4d4" :thinking-text "#808080"
                :selected-bg "#3a3a4a" :scrollbar-thumb "#3a3a4a"
                :user-message-bg "#343541"
                :user-message-text "#d4d4d4" :custom-message-bg "#2d2838"
                :custom-message-text "#d4d4d4" :custom-message-label "#9575cd"
                :tool-pending-bg "#282832" :tool-success-bg "#283228"
                :tool-error-bg "#3c2828" :tool-title "#d4d4d4"
                :tool-output "#808080"
                :md-heading "#f0c674" :md-link "#81a2be" :md-link-url "#666666"
                :md-code "#8abeb7" :md-code-block "#b5bd68"
                :md-code-block-border "#808080" :md-quote "#808080"
                :md-quote-border "#808080" :md-hr "#808080"
                :md-list-bullet "#8abeb7"
                :md-table-border "#808080"
                :tool-diff-added "#b5bd68" :tool-diff-removed "#cc6666"
                :tool-diff-context "#808080"
                :syntax-comment "#6A9955" :syntax-keyword "#569CD6"
                :syntax-function "#DCDCAA" :syntax-variable "#9CDCFE"
                :syntax-string "#CE9178" :syntax-number "#B5CEA8"
                :syntax-type "#4EC9B0" :syntax-operator "#D4D4D4"
                :syntax-punctuation "#D4D4D4"
                :thinking-off "#505050" :thinking-minimal "#6e6e6e"
                :thinking-low "#5f87af" :thinking-medium "#81a2be"
                :thinking-high "#b294bb" :thinking-xhigh "#d183e8"
                :thinking-max "#ff5fff"
                :bash-mode "#b5bd68"}
               color-mode)
         complete-fg (merge (:fg-map dark) fg-map)
         complete-bg (let [cb (merge (:bg-map dark) bg-map)]
                       ;; pi: scrollbarThumb ?? selectedBg — a theme that does
                       ;; not set scrollbarThumb inherits its OWN selectedBg
                       ;; (not the dark fallback) so light themes get a light
                       ;; thumb.
                       (if (contains? bg-map :scrollbar-thumb)
                         cb
                         (assoc cb :scrollbar-thumb (get cb :selected-bg))))]
     (map->Theme
      {:name name
       :fg-colors complete-fg
       :bg-colors complete-bg
       :color-mode color-mode
       :source-path source-path}))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Built-in themes — same color values as pi's dark.json / light.json
;; ═══════════════════════════════════════════════════════════════════════════

(def dark-theme
  (make-theme
   {:name "dark"
    :vars {"cyan" "#00d7ff" "blue" "#5f87ff" "green" "#b5bd68"
           "red" "#cc6666" "yellow" "#ffff00"
           "text" "#d4d4d4" "gray" "#808080" "dimGray" "#666666"
           "darkGray" "#505050" "accent" "#8abeb7"
           "selectedBg" "#3a3a4a" "userMsgBg" "#343541"
           "toolPendingBg" "#282832" "toolSuccessBg" "#283228"
           "toolErrorBg" "#3c2828" "customMsgBg" "#2d2838"}
    :colors {"accent" "accent" "border" "blue"
             "borderAccent" "cyan" "borderMuted" "darkGray"
             "success" "green" "error" "red" "warning" "yellow"
             "muted" "gray" "dim" "dimGray" "text" "text"
             "thinkingText" "gray"
             "selectedBg" "selectedBg" "userMessageBg" "userMsgBg"
             "userMessageText" "text"
             "customMessageBg" "customMsgBg" "customMessageText" "text"
             "customMessageLabel" "#9575cd"
             "toolPendingBg" "toolPendingBg" "toolSuccessBg" "toolSuccessBg"
             "toolErrorBg" "toolErrorBg" "toolTitle" "text"
             "toolOutput" "gray"
             "mdHeading" "#f0c674" "mdLink" "#81a2be"
             "mdLinkUrl" "dimGray" "mdCode" "accent"
             "mdCodeBlock" "green" "mdCodeBlockBorder" "gray"
             "mdQuote" "gray" "mdQuoteBorder" "gray" "mdHr" "gray"
             "mdListBullet" "accent"
             "toolDiffAdded" "green" "toolDiffRemoved" "red"
             "toolDiffContext" "gray"
             "syntaxComment" "#6A9955" "syntaxKeyword" "#569CD6"
             "syntaxFunction" "#DCDCAA" "syntaxVariable" "#9CDCFE"
             "syntaxString" "#CE9178" "syntaxNumber" "#B5CEA8"
             "syntaxType" "#4EC9B0" "syntaxOperator" "#D4D4D4"
             "syntaxPunctuation" "#D4D4D4"
             "thinkingOff" "darkGray" "thinkingMinimal" "#6e6e6e"
             "thinkingLow" "#5f87af" "thinkingMedium" "#81a2be"
             "thinkingHigh" "#b294bb" "thinkingXhigh" "#d183e8"
             "thinkingMax" "#ff5fff"
             "bashMode" "green"}}))

(def light-theme
  (make-theme
   {:name "light"
    :vars {"teal" "#5a8080" "blue" "#547da7"
           "green" "#588458" "red" "#aa5555" "yellow" "#9a7326"
           "text" "#1f2328" "mediumGray" "#6c6c6c" "dimGray" "#767676"
           "lightGray" "#b0b0b0"
           "selectedBg" "#d0d0e0" "userMsgBg" "#e8e8e8"
           "toolPendingBg" "#e8e8f0" "toolSuccessBg" "#e8f0e8"
           "toolErrorBg" "#f0e8e8" "customMsgBg" "#ede7f6"}
    :colors {"accent" "teal" "border" "blue"
             "borderAccent" "teal" "borderMuted" "lightGray"
             "success" "green" "error" "red" "warning" "yellow"
             "muted" "mediumGray" "dim" "dimGray" "text" "text"
             "thinkingText" "mediumGray"
             "selectedBg" "selectedBg" "userMessageBg" "userMsgBg"
             "userMessageText" "text"
             "customMessageBg" "customMsgBg" "customMessageText" "text"
             "customMessageLabel" "#7e57c2"
             "toolPendingBg" "toolPendingBg" "toolSuccessBg" "toolSuccessBg"
             "toolErrorBg" "toolErrorBg" "toolTitle" "text"
             "toolOutput" "mediumGray"
             "mdHeading" "yellow" "mdLink" "blue"
             "mdLinkUrl" "dimGray" "mdCode" "teal"
             "mdCodeBlock" "green" "mdCodeBlockBorder" "mediumGray"
             "mdQuote" "mediumGray" "mdQuoteBorder" "mediumGray" "mdHr" "mediumGray"
             "mdListBullet" "green"
             "toolDiffAdded" "green" "toolDiffRemoved" "red"
             "toolDiffContext" "mediumGray"
             "syntaxComment" "#008000" "syntaxKeyword" "#0000FF"
             "syntaxFunction" "#795E26" "syntaxVariable" "#001080"
             "syntaxString" "#A31515" "syntaxNumber" "#098658"
             "syntaxType" "#267F99" "syntaxOperator" "#000000"
             "syntaxPunctuation" "#000000"
             "thinkingOff" "lightGray" "thinkingMinimal" "#767676"
             "thinkingLow" "blue" "thinkingMedium" "teal"
             "thinkingHigh" "#875f87" "thinkingXhigh" "#8b008b"
             "thinkingMax" "#af005f"
             "bashMode" "green"}}))

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
  "Remove the theme NAME from the registry (extension unload — jar-ext.md
   §5; built-ins should never be unregistered). No-op when absent."
  [theme-name]
  (swap! themes dissoc (str theme-name))
  nil)

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
  "Load and register a theme from an EDN file at PATH; warnings print to
   stderr on failure (pi: loadThemeFromPath + register — the package-resource
   theme load unit)."
  [path]
  (try
    (register-theme! (load-theme-from-path path))
    (catch Exception e
      (binding [*out* *err*]
        (println "Warning: Failed to load theme" (fs/file-name path) ":" (ex-message e))))))

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
   resource unit). Same warning behavior as load-theme-file!."
  [paths]
  (doseq [p paths] (load-theme-file! p)))

(defn load-themes-from-dir
  "Load all .edn theme files from a directory."
  [dir]
  (load-theme-paths! (theme-files-in-dir dir)))

;; ═══════════════════════════════════════════════════════════════════════════
;; Validation (pi: parseThemeJson — required color tokens + name check)
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private optional-color-tokens
  "Tokens not required by pi's schema (Type.Optional)."
  #{:thinking-max})

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
;; subscribe through kmet.app.ui.subs/theme-sub (= this atom) instead of
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
