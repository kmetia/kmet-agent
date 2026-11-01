(ns kmet.test-theme
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.libs.terminal-image :as timg]
            [kmet.libs.theme :as theme]
            [kmet.test-tmp :as test-tmp]))

;; ─── Theme record ──────────────────────────────────────────────────────────

(t/deftest test-config-theme-name-resolves-through-the-store
  (t/is (= "dark" (:name (theme/get-theme (cfg/get-theme-name cfg/default-config)))))
  (t/is (= "light"
           (:name (theme/get-theme (cfg/get-theme-name
                                    (assoc cfg/default-config :theme "light")))))
        "the config :theme name selects the store's theme"))

(t/deftest test-theme-record
  (let [t theme/dark-theme]
    (t/is (instance? kmet.libs.theme.Theme t))
    (t/is (= "dark" (:name t)))
    (t/is (string? (get-in t [:fg-colors :text])))
    (t/is (string? (get-in t [:fg-colors :accent])))
    (t/is (string? (get-in t [:fg-colors :border])))
    (t/is (string? (get-in t [:fg-colors :error])))
    (t/is (string? (get-in t [:fg-colors :success])))
    (t/is (string? (get-in t [:fg-colors :warning])))))

(t/deftest test-light-theme
  (let [t theme/light-theme]
    (t/is (instance? kmet.libs.theme.Theme t))
    (t/is (= "light" (:name t)))
    (t/is (string? (get-in t [:fg-colors :accent])))))

;; ─── API accessors (pi: getFgAnsi / getColorMode / getThinkingBorderColor) ─

(t/deftest test-api-accessors
  (let [t theme/dark-theme]
    (t/is (= (get-in t [:fg-colors :accent]) (theme/get-fg-ansi t :accent)))
    (t/is (= (get-in t [:bg-colors :selected-bg]) (theme/get-bg-ansi t :selected-bg)))
    (t/is (contains? #{:truecolor :256color} (theme/get-color-mode t)))
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Unknown theme color"
           (theme/get-fg-ansi t :no-such-token)))
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Unknown theme background"
           (theme/get-bg-ansi t :no-such-token)))
    (t/testing "get-thinking-border-color maps every level to its token"
      (doseq [[level token] [[:off :thinking-off] [:minimal :thinking-minimal]
                             [:low :thinking-low] [:medium :thinking-medium]
                             [:high :thinking-high] [:xhigh :thinking-xhigh]
                             [:max :thinking-max]]]
        (let [f (theme/get-thinking-border-color t level)]
          (t/is (= (theme/fg t token "x") (f "x")))))
      (t/is (= (theme/fg t :thinking-off "x")
               ((theme/get-thinking-border-color t :unknown-level) "x"))
            "unknown levels map to :thinking-off like pi"))
    (t/testing "get-bash-mode-border-color"
      (t/is (= (theme/fg t :bash-mode "x")
               ((theme/get-bash-mode-border-color t) "x"))))))

(t/deftest test-thinking-max-falls-back-to-xhigh
  (t/testing "pi-schema: thinkingMax ?? thinkingXhigh (Type.Optional)"
    (let [colors (into {} (map (fn [k] [(name k) "#111111"]))
                       (concat theme/FG-TOKENS theme/BG-TOKENS))
          colors (assoc colors "thinkingXhigh" "#abcdef")
          colors (dissoc colors "thinking-max")
          t (theme/make-theme {:name "no-max" :colors colors})]
      (t/is (= (theme/get-fg-ansi t :thinking-xhigh)
               (theme/get-fg-ansi t :thinking-max))
            "thinkingMax falls back to thinkingXhigh, not the dark default")))
  (t/testing "flat schema"
    (let [t (theme/make-theme
             {:name "flat-no-max"
              :thinking-xhigh "#abcdef"
              :text "#ffffff"})]
      (t/is (= (theme/get-fg-ansi t :thinking-xhigh)
               (theme/get-fg-ansi t :thinking-max))))))

;; ─── pi color formats (pi: parseColor / okhslToRgb / oklchToRgb) ───────────

(t/deftest test-parse-color-hex
  (t/is (= {:kind :rgb :r 170 :g 187 :b 204} (theme/parse-color "#abc"))
        "three-digit shorthand")
  (t/is (= {:kind :rgb :r 255 :g 0 :b 0} (theme/parse-color "#ff0000")))
  (t/is (= {:kind :rgb :r 255 :g 0 :b 0} (theme/parse-color "#FF0000"))
        "hex is case-insensitive"))

(t/deftest test-parse-color-okhsl
  ;; pi's colors.test.ts: full saturation at the red cusp is pure sRGB red.
  (t/is (= {:kind :rgb :r 255 :g 0 :b 0}
           (theme/parse-color "okhsl(29.23 100% 56.8%)")))
  (t/is (= {:kind :rgb :r 78 :g 136 :b 194}
           (theme/parse-color "OKHSL(250deg 60% 55%)"))
        "uppercase, deg suffix, and 0-1 fractions without %")
  (t/is (= {:kind :rgb :r 78 :g 136 :b 194}
           (theme/parse-color "okhsl(250\t60%\t55%)"))
        "whitespace classes accept tabs, like pi's \\s")
  (t/is (= {:kind :rgb :r 222 :g 224 :b 225}
           (theme/parse-color "okhsl(234 3% 89%)"))
        "pi dark.json text color")
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"s must be between 0 and 1"
                          (theme/parse-color "okhsl(250 160% 55%)"))))

(t/deftest test-parse-color-oklch
  (t/is (= {:kind :rgb :r 28 :g 152 :b 158} (theme/parse-color "oklch(62% 0.1 200)")))
  (t/is (= {:kind :rgb :r 255 :g 0 :b 0}
           (theme/parse-color "oklch(0.627955 0.257683 29.2339)"))
        "gamut mapping at the red cusp")
  (t/is (= {:kind :rgb :r 255 :g 255 :b 255} (theme/parse-color "oklch(1 0.3 150)"))
        "out-of-gamut lightness maps to white")
  (t/is (= {:kind :rgb :r 0 :g 0 :b 0} (theme/parse-color "oklch(0 0.3 150)"))
        "zero lightness maps to black"))

(t/deftest test-parse-color-index-and-default
  (t/is (= {:kind :indexed :index 196} (theme/parse-color 196)))
  (t/is (nil? (theme/parse-color "")) "terminal default")
  (t/is (nil? (theme/parse-color nil)))
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid color value"
                          (theme/parse-color "red"))
        "a bare name is a variable reference, not a color"))

(t/deftest test-okhsl-256-index
  ;; pi's rgbToAnsi256 for the dark text color (okhsl 234 3% 89%).
  (let [t (theme/make-theme {:name "idx" :text "okhsl(234 3% 89%)"} nil :256color)]
    (t/is (.contains (get-in t [:fg-colors :text]) "38;5;254"))))

(t/deftest test-built-in-themes-match-pi-palette
  (t/testing "dark.json"
    (let [t (theme/make-theme @#'theme/dark-theme-data nil :truecolor)]
      (t/is (= "\u001b[38;2;222;224;225m" (get-in t [:fg-colors :text])))
      (t/is (= "\u001b[38;2;167;152;215m" (get-in t [:fg-colors :accent])))
      (t/is (= "\u001b[38;2;95;168;204m" (get-in t [:fg-colors :border])))
      (t/is (= "\u001b[48;2;33;59;73m" (get-in t [:bg-colors :user-message-bg])))
      (t/is (= "\u001b[38;2;72;78;82m" (get-in t [:fg-colors :scrollbar-track])))
      (t/is (= "\u001b[38;2;151;160;165m" (get-in t [:fg-colors :scrollbar-thumb])))))
  (t/testing "light.json"
    (let [t (theme/make-theme @#'theme/light-theme-data nil :truecolor)]
      (t/is (= "\u001b[38;2;59;63;65m" (get-in t [:fg-colors :text])))
      (t/is (= "\u001b[38;2;116;89;180m" (get-in t [:fg-colors :accent]))))))

(t/deftest test-optional-token-fallbacks
  (t/testing "scrollbarTrack inherits muted, scrollbarThumb/searchMatchText inherit text, searchMatchBg inherits selectedBg (pi: withThemeColorFallbacks)"
    (let [colors (into {} (map (fn [k] [(name k) "#111111"]))
                       (concat theme/FG-TOKENS theme/BG-TOKENS))
          colors (assoc colors "muted" "#010101" "text" "#020202" "selected-bg" "#030303")
          colors (dissoc colors "scrollbar-track" "scrollbar-thumb" "search-match-bg" "search-match-text")
          t (theme/make-theme {:name "fallbacks" :colors colors} nil :truecolor)]
      (t/is (= (theme/get-fg-ansi t :muted) (theme/get-fg-ansi t :scrollbar-track)))
      (t/is (= (theme/get-fg-ansi t :text) (theme/get-fg-ansi t :scrollbar-thumb)))
      (t/is (= (theme/get-fg-ansi t :text) (theme/get-fg-ansi t :search-match-text)))
      (t/is (= (theme/get-bg-ansi t :selected-bg) (theme/get-bg-ansi t :search-match-bg)))))
  (t/testing "a theme without a text color keeps the dark scrollbarThumb"
    (let [dark (theme/make-theme @#'theme/dark-theme-data nil :truecolor)
          t (theme/make-theme {:name "no-text" :accent "#ff0000"} nil :truecolor)]
      (t/is (= (theme/get-fg-ansi dark :scrollbar-thumb)
               (theme/get-fg-ansi t :scrollbar-thumb))))))

(t/deftest test-var-refs
  (t/testing "chained variable references resolve"
    (let [t (theme/make-theme
             {:name "chain"
              :vars {"primary" "#ff0000" "alias" "primary"}
              :colors {"text" "alias"}} nil :truecolor)]
      (t/is (= "\u001b[38;2;255;0;0m" (get-in t [:fg-colors :text])))))
  (t/testing "missing and circular references throw"
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Variable reference not found"
           (theme/make-theme {:name "missing" :colors {"text" "nope"}} nil :truecolor)))
    (t/is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Circular variable reference detected"
           (theme/make-theme {:name "cycle"
                              :vars {"a" "b" "b" "a"}
                              :colors {"text" "a"}} nil :truecolor)))))

;; ─── Make theme ────────────────────────────────────────────────────────────

(t/deftest test-make-theme
  (let [t (theme/make-theme
           {:name "test"
            :text "#ff0000"
            :accent "#0000ff"
            :success "#00ff00"
            :error "#ff0000"
            :border "#505050"} nil :truecolor)]
    (t/is (= "test" (:name t)))
    (t/is (.contains (get-in t [:fg-colors :text]) "38;2;255;0;0"))
    (t/is (.contains (get-in t [:fg-colors :accent]) "38;2;0;0;255"))
    (t/is (.contains (get-in t [:fg-colors :success]) "38;2;0;255;0"))
    (t/is (.contains (get-in t [:fg-colors :error]) "38;2;255;0;0"))))

(t/deftest test-make-theme-hex
  (let [t (theme/make-theme
           {:name "hex"
            :text "#ff0000"
            :accent "#00ff00"
            :border "#0000ff"} nil :truecolor)]
    (t/is (.contains (get-in t [:fg-colors :text]) "38;2;255;0;0"))
    (t/is (.contains (get-in t [:fg-colors :accent]) "38;2;0;255;0"))
    (t/is (.contains (get-in t [:fg-colors :border]) "38;2;0;0;255"))))

(t/deftest test-make-theme-256
  (let [t (theme/make-theme
           {:name "256"
            :text 196
            :accent 46
            :border 21})]
    (t/is (.contains (get-in t [:fg-colors :text]) "38;5;196"))
    (t/is (.contains (get-in t [:fg-colors :accent]) "38;5;46"))
    (t/is (.contains (get-in t [:fg-colors :border]) "38;5;21"))))

(t/deftest test-make-theme-256-from-hex
  ;; Hex colors under a 256-color mode resolve through rgb->256 — including
  ;; the grayscale branch, where Math/round on the (long) quotient used to
  ;; throw in babashka ("No matching method round").
  (let [t (theme/make-theme
           {:name "hex-256"
            :text "#808080"          ;; grayscale → the 232+gray ramp
            :accent "#ff0000"
            :border "#000000"} nil :256color)]
    (t/is (.contains (get-in t [:fg-colors :text]) "38;5;"))
    (t/is (not (.contains (get-in t [:fg-colors :text]) "38;2;")))
    (t/is (.contains (get-in t [:fg-colors :accent]) "38;5;196"))))

(t/deftest test-detect-color-mode
  ;; The theme mode comes from the shared terminal-image capabilities
  ;; (pi: getCapabilities().trueColor), which fold in COLORTERM and the known
  ;; true-color terminal programs).
  (let [detect @#'theme/detect-color-mode]
    (with-redefs [timg/get-capabilities
                  (fn [] {:images nil :true-color true :hyperlinks false})]
      (t/is (= :truecolor (detect)) "true-color capability → truecolor"))
    (with-redefs [timg/get-capabilities
                  (fn [] {:images nil :true-color false :hyperlinks false})]
      (t/is (= :256color (detect)) "no true-color → safe 256-color default"))))

(t/deftest test-make-theme-detects-capability
  (with-redefs [timg/get-capabilities
                (fn [] {:images nil :true-color true :hyperlinks false})]
    (t/is (= :truecolor
             (theme/get-color-mode (theme/make-theme {:name "auto" :text "#ff0000"})))
          "make-theme with no explicit mode uses the detected capability"))
  (with-redefs [timg/get-capabilities
                (fn [] {:images nil :true-color false :hyperlinks false})]
    (t/is (= :256color
             (theme/get-color-mode (theme/make-theme {:name "auto" :text "#ff0000"}))))))

(t/deftest test-make-theme-truecolor-vector
  (let [t (theme/make-theme
           {:name "truecolor"
            :text "#ff0000"
            :accent "#00ff00"
            :border "#0000ff"} nil :truecolor)]
    (t/is (.contains (get-in t [:fg-colors :text]) "38;2;255;0;0"))
    (t/is (.contains (get-in t [:fg-colors :accent]) "38;2;0;255;0"))
    (t/is (.contains (get-in t [:fg-colors :border]) "38;2;0;0;255"))))

(t/deftest test-make-theme-nil-color
  (let [t (theme/make-theme
           {:name "nil-test"
            :text nil
            :accent nil} nil :truecolor)]
    ;; nil means "use default" — falls back to pi's dark palette
    (t/is (= "\u001b[38;2;222;224;225m" (get-in t [:fg-colors :text])))
    (t/is (= "\u001b[38;2;167;152;215m" (get-in t [:fg-colors :accent])))))

(t/deftest test-make-theme-with-bg
  (let [t (theme/make-theme
           {:name "bg-test"
            :user-message-bg "#505050"
            :custom-message-bg nil
            :selected-bg "#00ffff"} nil :truecolor)]
    (t/is (.contains (get-in t [:bg-colors :user-message-bg]) "48;2;80;80;80"))
    ;; nil bg means "use default" — falls back to pi's dark palette
    (t/is (= "\u001b[48;2;58;48;85m" (get-in t [:bg-colors :custom-message-bg])))
    (t/is (.contains (get-in t [:bg-colors :selected-bg]) "48;2;0;255;255"))))

(t/deftest test-thinking-levels
  (let [t (theme/make-theme
           {:name "think-test"
            :thinking-low 240
            :thinking-medium 245
            :thinking-high 250
            :thinking-xhigh 255})]
    (t/is (.contains (get-in t [:fg-colors :thinking-low]) "38;5;240"))
    (t/is (.contains (get-in t [:fg-colors :thinking-medium]) "38;5;245"))
    (t/is (.contains (get-in t [:fg-colors :thinking-high]) "38;5;250"))
    (t/is (.contains (get-in t [:fg-colors :thinking-xhigh]) "38;5;255"))))

;; ─── Theme registry ────────────────────────────────────────────────────────

(t/deftest test-get-theme-default
  (let [t (theme/get-theme "dark")]
    (t/is (some? t))
    (t/is (= "dark" (:name t)))))

(t/deftest test-get-theme-light
  (let [t (theme/get-theme "light")]
    (t/is (some? t))
    (t/is (= "light" (:name t)))))

(t/deftest test-get-theme-unknown-fallback
  (let [t (theme/get-theme "nonexistent")]
    (t/is (some? t))
    (t/is (= "dark" (:name t)))))

(t/deftest test-register-theme
  (let [t (theme/make-theme {:name "custom" :accent "#ff00ff"} nil :truecolor)]
    (theme/register-theme! t)
    (let [loaded (theme/get-theme "custom")]
      (t/is (some? loaded))
      (t/is (= "custom" (:name loaded)))
      (t/is (.contains (get-in loaded [:fg-colors :accent]) "38;2;255;0;255")))))

;; ─── Theme file loading ───────────────────────────────────────────────────

(t/deftest test-load-themes-from-dir-non-existent
  (t/testing "Loading from a non-existent dir loads nothing and does not throw"
    (t/is (= [] (theme/load-themes-from-dir "/nonexistent/themes")))))

(defn- complete-theme-edn
  "A theme map with every required color token (pi schema requires them
   all except :thinking-max)."
  [theme-name]
  (let [colors (into {} (map (fn [k] [(name k) "#000000"]))
                     (concat theme/FG-TOKENS theme/BG-TOKENS))]
    {:name theme-name :colors colors}))

(t/deftest test-load-themes-from-dir
  (let [tmp-dir (test-tmp/temp-dir! "test-themes-")]
    (io/make-parents (str tmp-dir "/test.edn"))
    ;; Write a valid (complete) theme file
    (spit (str tmp-dir "/test.edn") (pr-str (complete-theme-edn "test")))
    ;; Write a non-edn file (should be ignored)
    (spit (str tmp-dir "/not-a-theme.txt") "hello")
    (theme/load-themes-from-dir tmp-dir)
    (let [loaded (theme/get-theme "test")]
      (t/is (some? loaded))
      (t/is (= "test" (:name loaded))))
    ;; Cleanup — scoped to this run's dir (walking all of target/ was ~1 s)
    (fs/delete-tree tmp-dir)))

(t/deftest test-load-themes-from-dir-rejects-partial
  (t/testing "a theme missing required tokens fails with the sorted list
              (pi: Missing required color tokens)"
    (let [tmp-dir (test-tmp/temp-dir! "test-themes-partial-")
          out (java.io.StringWriter.)]
      (io/make-parents (str tmp-dir "/partial.edn"))
      (spit (str tmp-dir "/partial.edn") "{:name \"partial\" :text \"#000000\"}")
      (binding [*out* out *err* out]
        (theme/load-themes-from-dir tmp-dir))
      (t/is (not= "partial" (:name (theme/get-theme "partial")))
            "rejected — falls back to dark")
      (let [msg (str out)]
        (t/is (str/includes? msg "Missing required color tokens:") "reports the missing-token header")
        (t/is (str/includes? msg "- accent") "sorted list includes the first missing token")
        (t/is (str/includes? msg "- tool-error-bg") "sorted list includes the last missing token"))
      ;; Cleanup — scoped to this run's dir
      (fs/delete-tree tmp-dir))))

;; ─── Terminal theme detection (pi: detection section) ──────────────────────

(t/deftest test-get-theme-for-rgb-color
  (t/is (= :dark (theme/get-theme-for-rgb-color {:r 0 :g 0 :b 0})) "black → dark")
  (t/is (= :light (theme/get-theme-for-rgb-color {:r 255 :g 255 :b 255})) "white → light")
  (t/is (= :dark (theme/get-theme-for-rgb-color {:r 128 :g 128 :b 128})) "mid-gray luminance ≈ 0.216 < 0.5")
  (t/is (= :dark (theme/get-theme-for-rgb-color {:r 100 :g 100 :b 100}))))

(t/deftest test-detect-terminal-background-from-env
  (t/testing "COLORFGBG background index drives the theme"
    (t/is (= :dark (:theme (theme/detect-terminal-background-from-env {"COLORFGBG" "15;0"})))
          "bg index 0 (black) → dark")
    (t/is (= :light (:theme (theme/detect-terminal-background-from-env {"COLORFGBG" "15;15"})))
          "bg index 15 (white) → light")
    (t/is (= :high (:confidence (theme/detect-terminal-background-from-env {"COLORFGBG" "15;7"})))))
  (t/testing "no hint → dark fallback with low confidence"
    (let [det (theme/detect-terminal-background-from-env {})]
      (t/is (= :dark (:theme det)))
      (t/is (= :low (:confidence det))))))

;; ─── Auto light/dark setting (pi: parseAutoThemeSetting) ───────────────────

(t/deftest test-parse-auto-theme-setting
  (t/is (= {:light-theme "light" :dark-theme "dark"}
           (theme/parse-auto-theme-setting "light/dark")))
  (t/is (= {:light-theme "paper" :dark-theme "midnight"}
           (theme/parse-auto-theme-setting " paper / midnight ")))
  (t/is (nil? (theme/parse-auto-theme-setting "dark")) "no slash")
  (t/is (nil? (theme/parse-auto-theme-setting "a/b/c")) "two slashes")
  (t/is (nil? (theme/parse-auto-theme-setting "/dark")) "empty light side")
  (t/is (nil? (theme/parse-auto-theme-setting "light/")) "empty dark side")
  (t/is (nil? (theme/parse-auto-theme-setting nil))))

(t/deftest test-resolve-theme-setting
  (t/is (= "dark" (theme/resolve-theme-setting "light/dark" :dark)))
  (t/is (= "light" (theme/resolve-theme-setting "light/dark" :light)))
  (t/is (= "paper" (theme/resolve-theme-setting "paper" :dark)) "plain name passes through")
  (t/is (nil? (theme/resolve-theme-setting "a/b/c" :dark)) "invalid auto setting → nil")
  (t/is (nil? (theme/resolve-theme-setting nil :dark))))

;; ─── Current theme state (pi: initTheme / setTheme / setThemeInstance) ─────

(t/deftest test-theme-state
  (let [orig-name (theme/get-current-theme-name)
        orig-theme (theme/get-current-theme)]
    (try
      (t/testing "init-theme! switches with dark fallback"
        (theme/init-theme! "light")
        (t/is (= "light" (:name (theme/get-current-theme)))))
      (t/testing "set-theme! reports unknown names"
        (let [result (theme/set-theme! "no-such-theme")]
          (t/is (false? (:success result)))
          (t/is (= "Theme not found: no-such-theme" (:error result)))
          (t/is (= "dark" (theme/get-current-theme-name)) "falls back to dark")))
      (t/testing "set-theme-instance! switches in-memory and stops the watcher"
        (theme/set-theme-instance! theme/light-theme)
        (t/is (= "<in-memory>" (theme/get-current-theme-name)))
        (t/is (identical? theme/light-theme (theme/get-current-theme))))
      (t/testing "on-theme-change fires after set-theme!"
        (let [changes (atom [])]
          (theme/on-theme-change #(swap! changes conj (theme/get-current-theme-name)))
          (theme/set-theme! "light")
          (t/is (= ["light"] @changes))
          (theme/on-theme-change nil)))
      (finally
        (theme/on-theme-change nil)
        (theme/set-theme-instance! orig-theme)
        (theme/init-theme! orig-name)))))

(t/deftest test-unregister-theme
  (let [t (theme/make-theme {:name "tmp-ext-theme" :accent "#ffffff"})]
    (theme/register-theme! t)
    (t/is (some? (theme/get-theme-by-name "tmp-ext-theme")))
    (theme/unregister-theme! "tmp-ext-theme")
    (t/is (nil? (theme/get-theme-by-name "tmp-ext-theme")))
    (t/is (some? (theme/get-theme-by-name "dark")) "built-ins untouched")
    (theme/unregister-theme! "never-there") ;; no-op
    (t/is (some? (theme/get-theme-by-name "light")))))

(t/deftest test-unregister-theme-value-guard
  ;; The guarded removal is what a contributed theme's owner teardown uses: a
  ;; later registration of the same name must survive it.
  (let [first-theme (theme/make-theme {:name "guard-theme" :accent "#ffffff"})
        later-theme (theme/make-theme {:name "guard-theme" :accent "#000000"})]
    (theme/register-theme! first-theme)
    (theme/register-theme! later-theme)
    (theme/unregister-theme! "guard-theme" first-theme)
    (t/is (identical? later-theme (theme/get-theme-by-name "guard-theme"))
          "one contributor's teardown cannot drop another's registration")
    (theme/unregister-theme! "guard-theme" later-theme)
    (t/is (nil? (theme/get-theme-by-name "guard-theme")) "its own registration goes")))

