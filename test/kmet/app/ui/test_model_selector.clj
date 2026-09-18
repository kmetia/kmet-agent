(ns kmet.app.ui.test-model-selector
  "Model selector tests — visible search filter, arrow navigation (rows
   rebuild, pi updateList), current-model checkmark + initial selection,
   Enter select / Escape cancel, and the all/scoped Tab toggle."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as kb]
            [kmet.app.ui.model-selector :as ms]
            [kmet.ai.models :as models]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.macros :as macros]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(defn- model [provider id]
  (models/map->Model {:id id :name (str "Model " id) :provider provider
                      :api :openai-completions :base-url "https://x"
                      :reasoning false :input [:text]
                      :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                      :context-window 1000 :max-tokens 100}))

(defn- selector
  "Build a selector over p1/a p1/b p2/x; CURRENT is the in-use model (sorted
   first, marked ✓) and may be omitted (defaults to p2/x)."
  [& {:keys [models scoped current search on-select on-cancel]}]
  (let [dir (str (fs/create-dirs (fs/path "target" "test-model-selector-keybindings")))]
    (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir)))
  (ms/make-model-selector
   (or models [(model :p1 "a") (model :p1 "b") (model :p2 "x")])
   (or scoped [])
   (or current (model :p2 "x"))
   :search search
   :on-select on-select
   :on-cancel on-cancel))

(defn- press [sel key]
  ;; keys are fed as raw terminal input (pi parseKey); maps normalized ids to
  ;; the byte sequences the TUI would deliver
  (protocols/handle-input
   sel
   (case key
     "enter" "\r"
     "escape" "\u001b"
     "up" "\u001b[A"
     "down" "\u001b[B"
     "tab" "\t"
     "ctrl+c" "\u0003"
     key)))

(defn- render-lines
  "The selector's rendered lines, ANSI-stripped."
  [sel]
  (mapv u/strip-ansi-codes (protocols/render sel 120)))

(defn- rows
  "The rendered list region between the search input and the bottom border
   (rows, scroll counter and info block), with the same indexes the old
   list-container children had."
  [sel]
  (let [after-input (drop-while #(not (str/starts-with? % "> ")) (render-lines sel))]
    (vec (drop 1 (take-while #(not (re-matches #"[─]+" (str/trim %)))
                             (drop 1 after-input))))))

(defn- row-text
  "Text of the i-th rendered row line, ANSI stripped."
  [sel i]
  (nth (rows sel) i))

(defn- search-value
  "The live filter text: the panel mirrors the tag-owned input's text into
   the state's :search after every forwarded key, so that is the readable
   instance-state for assertions."
  [sel]
  (:search @(:state-atom sel)))

;; ─── handle-input behaviors ────────────────────────────────────────────────

(t/deftest test-current-model-selected-and-marked
  ;; p2/x is current; sorted current-first → first row, marked with ✓
  (let [sel (selector)]
    (t/is (str/includes? (row-text sel 0) "→") "the current model is selected")
    (t/is (str/includes? (row-text sel 0) "✓") "and marked with the checkmark")))

(t/deftest test-scoped-current-model-selected
  ;; scoped scope: the initial selection lands on the current model within
  ;; the scoped list (pi loadModelsFromSnapshot uses the active list) — the
  ;; all-models index must not be reused for the scoped list
  (let [sel (selector :scoped [(model :p1 "b") (model :p2 "x")]
                      :current (model :p2 "x"))]
    (t/is (str/includes? (row-text sel 1) "→") "the current model is selected")
    (t/is (str/includes? (row-text sel 1) "✓") "and marked")
    (t/is (not (str/includes? (row-text sel 0) "→"))
          "the first scoped row is not selected")))

(t/deftest test-arrow-keys-move-selection
  ;; navigation must rebuild the rows (pi updateList), not just move the
  ;; state — the rendered selection arrow follows the selection
  (let [sel (selector)]
    (press sel "down")
    (t/is (str/includes? (row-text sel 1) "→") "down moves the arrow to the next row")
    (t/is (not (str/includes? (row-text sel 0) "→")) "and off the previous row")
    (press sel "up")
    (t/is (str/includes? (row-text sel 0) "→") "up moves the arrow back")
    (press sel "down") (press sel "down") (press sel "down")
    (t/is (str/includes? (row-text sel 0) "→") "down wraps to the top at the bottom")))

(t/deftest test-search-input-filters-visibly
  ;; typing goes to the visible search input; the list narrows and the
  ;; selection jumps to the best match (pi filterModels)
  (let [sel (selector)]
    (press sel "x")
    (t/is (= "x" (search-value sel)) "the filter text is visible in the search input")
    (t/is (str/includes? (row-text sel 0) "x [p2]") "the list narrows to the match")
    (t/is (str/includes? (row-text sel 0) "→") "selection moves to the top match")))

(t/deftest test-pre-filled-search
  (let [sel (selector :search "qwen")]
    (t/is (= "qwen" (search-value sel)) "search-term pre-fills the input")
    (t/is (str/includes? (row-text sel 0) "No matching models")
          "no match shows the empty state")))

(t/deftest test-enter-selects
  ;; sorted current-first: p2/x, p1/a, p1/b — down highlights p1/a
  (let [selected (atom ::none)
        sel (selector :on-select (fn [m] (reset! selected m)))]
    (press sel "down")
    (press sel "enter")
    (t/is (= "a" (:id @selected)) "Enter hands the highlighted model to on-select")))

(t/deftest test-enter-with-no-match-is-noop
  ;; filtering to nothing must not fire on-select with nil (pi guards on
  ;; selectedModel — a nil model would wipe the agent's provider)
  (let [selected (atom ::none)
        sel (selector :on-select (fn [m] (reset! selected m)))]
    (press sel "z")
    (t/is (str/includes? (row-text sel 0) "No matching models"))
    (press sel "enter")
    (t/is (= ::none @selected) "Enter with no match does nothing")))

(t/deftest test-escape-cancels
  (let [cancelled (atom false)
        sel (selector :on-cancel (fn [] (reset! cancelled true)))]
    (press sel "escape")
    (t/is (true? @cancelled))))

(t/deftest test-ctrl-c-cancels
  (let [cancelled (atom false)
        sel (selector :on-cancel (fn [] (reset! cancelled true)))]
    (press sel "ctrl+c")
    (t/is (true? @cancelled) "Ctrl+C cancels (pi tui.select.cancel)")))

(t/deftest test-scope-defaults-scoped-and-toggles
  ;; scoped models set → opens scoped; Tab toggles all/scoped. Current model
  ;; p1/a is not in the scoped list (so the scope change is visible).
  (let [sel (selector :scoped [(model :p2 "x")] :current (model :p1 "a"))]
    (t/is (str/includes? (row-text sel 0) "x [p2]") "scoped scope by default")
    (press sel "tab")
    (t/is (str/includes? (row-text sel 0) "a [p1] ✓") "Tab toggles to all (current first)")
    (press sel "tab")
    (t/is (str/includes? (row-text sel 0) "x [p2]") "Tab toggles back to scoped")))

(t/deftest test-no-scope-without-scoped-models
  ;; no scoped models → scope text is absent; Tab is a no-op
  (let [sel (selector)]
    (t/is (not-any? #(str/includes? % "Scope:") (render-lines sel))
          "no scope line without scoped models")
    (press sel "tab")
    (t/is (str/includes? (row-text sel 0) "x [p2]") "Tab does nothing without scoped models")))

(t/deftest test-model-info-lines
  ;; The selected model's info lines: the name line plus the cost line,
  ;; the rates marked with the footer's direction marks (↑ in · ↓ out ·
  ;; C↑ cache read · C↓ cache write); unpriced models render
  ;; "Cost: free" instead of nothing
  (let [priced (assoc (model :p1 "a")
                      :cost {:input 0.44 :output 1.5 :cache-read 0.11 :cache-write 3.3})
        sel (selector :models [priced (model :p1 "b") (model :p2 "x")]
                      :current priced)]
    (t/is (str/includes? (row-text sel 4) "Model Name: Model a")
          "the name line under the rows")
    (t/is (str/includes? (row-text sel 5) "↑$0.44") "input rate marked ↑")
    (t/is (str/includes? (row-text sel 5) "↓$1.5") "output rate marked ↓")
    (t/is (str/includes? (row-text sel 5) "C↑$0.11") "cache read marked C↑")
    (t/is (str/includes? (row-text sel 5) "C↓$3.3") "cache write marked C↓")
    (press sel "down")
    (t/is (str/includes? (row-text sel 5) "Cost: free")
          "unpriced model renders the free line")))

(t/deftest test-row-rebuilds-do-not-leak-watches
  ;; Rows re-derive per navigation (pi updateList). The retired rows must be
  ;; disposed — a dropped Text keeps its track! watch registered, which
  ;; would grow the registry per keypress and pin the components.
  (let [sel (selector)
        watchers #(count @(deref #'macros/watch-registry))]
    (core/render sel 80)
    (let [baseline (watchers)]
      (dotimes [_ 6] (press sel "down") (core/render sel 80))
      (t/is (= baseline (watchers))
            "steady state: navigation does not accumulate watches"))))

(t/deftest test-root-body-memoizes-idle-frames
  ;; The state atom is read through tracked-deref: an idle re-render hands
  ;; back the cached tree (0 bodies run) and a state change re-derives once.
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
