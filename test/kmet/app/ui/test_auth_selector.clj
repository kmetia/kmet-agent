(ns kmet.app.ui.test-auth-selector
  "Auth-selector tests — provider rows, status indicators, [subscription]/
   [API key] labels, clamped navigation, Enter/Escape, the search filter and
   empty states (pi OAuthSelectorComponent), plus the auth-method selector
   (pi ExtensionSelectorComponent) rows and hint. Render-driven: assertions
   read the ANSI-stripped rendered lines, not component internals."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as kb]
            [kmet.app.ui.auth-selector :as auth-selector]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.macros :as macros]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(defn- install-keybindings! []
  (let [dir (str (fs/create-dirs (fs/path "target" "test-auth-selector-keybindings")))]
    (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir))))

(defn- entry
  [id & {:keys [name auth-type status method-name]}]
  {:id id
   :name (or name id)
   :auth-type (or auth-type :oauth)
   :method-name method-name
   :status status})

(defn- selector
  "Auth provider selector over the given ENTRIES (defaults: two providers,
   both unconfigured)."
  [& {:keys [mode entries search on-select on-cancel]}]
  (install-keybindings!)
  (auth-selector/make-auth-selector
   (or mode :login)
   (or entries [(entry "anthropic" :name "Anthropic")
                (entry "openai" :name "OpenAI" :auth-type :api-key)])
   on-select on-cancel search))

(defn- press
  "Feed a raw terminal key sequence (pi parseKey inputs)."
  [sel key]
  (protocols/handle-input
   sel
   (case key
     "enter" "\r"
     "escape" "\u001b"
     "up" "\u001b[A"
     "down" "\u001b[B"
     key)))

(defn- render-lines [sel]
  (mapv u/strip-ansi-codes (protocols/render sel 120)))

(defn- rows
  "The rendered provider rows (chrome and blank lines stripped)."
  [sel]
  (->> (render-lines sel)
       (drop-while #(not (str/starts-with? % "> ")))
       (drop 1)
       (remove str/blank?)
       (take-while #(not (re-matches #"[─]+" (str/trim %))))
       vec))

(defn- row-line [sel i] (nth (rows sel) i))

(defn- arrow-line
  "The rendered row carrying the selection arrow (present in both
   selectors' row areas only)."
  [sel]
  (first (filter #(str/includes? % "→") (render-lines sel))))

;; ─── Provider selector (pi OAuthSelectorComponent) ────────────────────────

(t/deftest test-login-layout-title-and-status-indicators
  (let [sel (selector :entries [(entry "anthropic" :name "Anthropic"
                                       :status {:type :oauth :source "OAuth"})
                                (entry "openai" :name "OpenAI" :auth-type :api-key
                                       :status {:type :api-key :source "OPENAI_API_KEY"})
                                (entry "groq" :name "Groq" :auth-type :api-key)])]
    (t/is (some #(str/includes? % "Select provider to configure:") (render-lines sel))
          "login title")
    (t/is (str/includes? (row-line sel 0) "→ Anthropic") "first row selected")
    (t/is (str/includes? (row-line sel 0) "✓ configured")
          "a stored/OAuth status renders ✓ configured")
    (t/is (str/includes? (row-line sel 1) "✓ env: OPENAI_API_KEY")
          "env-var sources render ✓ env: VAR")
    (t/is (str/includes? (row-line sel 2) "• unconfigured")
          "no status renders • unconfigured")
    (t/is (str/includes? (row-line sel 2) "[API key]")
          "both auth types present → type labels shown")))

(t/deftest test-logout-wording-and-mismatch-warning
  (let [sel (selector :mode :logout
                      :entries [(entry "anthropic" :name "Anthropic" :auth-type :api-key
                                       :status {:type :oauth :source "OAuth"})])]
    (t/is (some #(str/includes? % "Select provider to logout:") (render-lines sel))
          "logout title")
    (t/is (str/includes? (row-line sel 0) "• subscription configured")
          "a status of the other type is a muted warning")
    (t/is (not (str/includes? (row-line sel 0) "[subscription]"))
          "a single auth type renders no type label")))

(t/deftest test-empty-states
  (let [login (selector :mode :login :entries [])
        logout (selector :mode :logout :entries [])]
    (t/is (str/includes? (row-line login 0) "No providers available"))
    (t/is (str/includes? (row-line logout 0) "No providers logged in. Use /login first."))))

(t/deftest test-navigation-clamps-without-wrapping
  (let [sel (selector)]
    (press sel "up")
    (t/is (str/includes? (arrow-line sel) "Anthropic") "up at the top stays")
    (press sel "down")
    (t/is (str/includes? (arrow-line sel) "OpenAI") "down moves the arrow")
    (press sel "down")
    (press sel "down")
    (t/is (str/includes? (arrow-line sel) "OpenAI") "down at the bottom stays")))

(t/deftest test-enter-selects-and-escape-cancels
  (let [selected (atom ::none)
        sel (selector :on-select (fn [id auth-type] (reset! selected [id auth-type]))
                      :on-cancel (fn [] (reset! selected ::cancelled)))]
    (press sel "down")
    (press sel "enter")
    (t/is (= ["openai" :api-key] @selected) "Enter hands [id auth-type] to on-select")
    (press sel "escape")
    (t/is (= ::cancelled @selected) "Escape cancels")))

(t/deftest test-search-filters-and-keeps-selection-in-bounds
  (let [sel (selector :entries [(entry "anthropic" :name "Anthropic")
                                (entry "openai" :name "OpenAI")
                                (entry "groq" :name "Groq")])]
    ;; move to the last row, then filter down to a single match
    (press sel "down")
    (press sel "down")
    (doseq [c ["p" "e" "n"]] (press sel c))
    (t/is (str/includes? (arrow-line sel) "OpenAI")
          "the clamped index follows the narrowed list (pi does not reset to 0)")
    (press sel "z")
    (t/is (str/includes? (row-line sel 0) "No matching providers")
          "no match shows the empty state")))

(t/deftest test-pre-filled-search
  (let [sel (selector :search "open")]
    (t/is (some #(str/includes? % "> open") (render-lines sel))
          "the pre-filled search is visible in the input")
    (t/is (str/includes? (row-line sel 0) "OpenAI") "the list starts filtered")))

(t/deftest test-scroll-counter
  ;; more entries than the 8-row viewport: the counter shows (selected/total)
  (let [sel (selector :entries (mapv (fn [i] (entry (str "p" i) :name (str "Provider " i)))
                                     (range 20)))]
    (t/is (some #(str/includes? % "(1/20)") (rows sel)) "the scroll counter shows")))

(t/deftest test-root-body-memoizes-idle-frames
  (let [sel (selector)]
    (protocols/render sel 120)
    (hiccup/reset-counters!)
    (protocols/render sel 120)
    (t/is (zero? (:bodies-run (hiccup/counters))) "idle frame: body cached")
    (t/is (= 1 (:bodies-skipped (hiccup/counters))))
    (hiccup/reset-counters!)
    (press sel "down")
    (protocols/render sel 120)
    (t/is (= 1 (:bodies-run (hiccup/counters))) "state change re-derives once")))

(t/deftest test-navigation-does-not-leak-watches
  ;; re-derived rows must be disposed — a dropped Text keeps its track! watch
  ;; registered, which would grow the registry per keypress
  (let [sel (selector)
        watchers #(count @(deref #'macros/watch-registry))]
    (protocols/render sel 120)
    (let [baseline (watchers)]
      (dotimes [_ 6] (press sel "down") (protocols/render sel 120))
      (t/is (= baseline (watchers))
            "steady state: navigation does not accumulate watches"))))

(t/deftest test-dispose-unwinds-the-root-and-input
  ;; The close paths call dispose: rendering registers track! watches (rows +
  ;; input) that must not outlive the panel — the dock drops foreign records
  ;; without disposing them, so an unwired root would keep the reaction and
  ;; its children alive forever.
  (let [sel (selector)
        watchers #(count @(deref #'macros/watch-registry))
        before (watchers)]
    (protocols/render sel 120)
    (t/is (> (watchers) before) "a rendered panel registers watches")
    (protocols/dispose sel)
    (t/is (= before (watchers)) "dispose removes exactly them")
    (protocols/dispose sel)
    (t/is (= before (watchers)) "dispose is idempotent")))

;; ─── Auth-method selector (pi ExtensionSelectorComponent) ─────────────────

(defn- method-selector
  [& {:keys [options on-select on-cancel]}]
  (install-keybindings!)
  (auth-selector/make-auth-method-selector
   "Select authentication method:"
   (or options ["Sign in with an account" "Sign in with an API key"])
   on-select on-cancel))

(t/deftest test-method-selector-rows-hint-and-navigation
  (let [sel (method-selector)]
    (t/is (some #(str/includes? % "Select authentication method:") (render-lines sel)))
    (t/is (str/includes? (arrow-line sel) "Sign in with an account") "first option selected")
    (press sel "j")
    (t/is (str/includes? (arrow-line sel) "API key") "j moves down (pi key)")
    (press sel "down")
    (t/is (str/includes? (arrow-line sel) "API key") "down at the bottom clamps")
    (press sel "k")
    (t/is (str/includes? (arrow-line sel) "account") "k moves up")
    (press sel "up")
    (t/is (str/includes? (arrow-line sel) "account") "up at the top clamps")
    (t/is (some #(str/includes? % "navigate") (render-lines sel))
          "the hint row renders")))

(t/deftest test-method-selector-select-and-cancel
  (let [chosen (atom ::none)
        sel (method-selector :on-select (fn [label] (reset! chosen label))
                             :on-cancel (fn [] (reset! chosen ::cancelled)))]
    (press sel "down")
    (press sel "enter")
    (t/is (= "Sign in with an API key" @chosen) "Enter selects the highlighted label")
    (press sel "escape")
    (t/is (= ::cancelled @chosen) "Escape cancels")))

(t/deftest test-method-selector-memoizes-idle-frames
  (let [sel (method-selector)]
    (protocols/render sel 120)
    (hiccup/reset-counters!)
    (protocols/render sel 120)
    (t/is (zero? (:bodies-run (hiccup/counters))) "idle frame: body cached")
    (hiccup/reset-counters!)
    (press sel "down")
    (protocols/render sel 120)
    (t/is (= 1 (:bodies-run (hiccup/counters)))
          "selection change re-derives once")))
