(ns kmet.app.ui.test-thinking-selector
  "Thinking-selector tests — row rendering (arrow selection, ✓ active level,
   · default note), arrow navigation, Enter selects / Ctrl+S persists / Esc
   cancels, and the search filter (pi ThinkingSelectorComponent)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as kb]
            [kmet.app.ui.thinking-selector :as ts]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.macros :as macros]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(defn- selector
  "Build a selector over off/low/medium/high with global keybindings
   installed. Defaults: current :medium, default :off."
  [& {:keys [levels current default on-select on-persist on-cancel]}]
  (let [dir (str (fs/create-dirs (fs/path "target" "test-thinking-selector-keybindings")))]
    (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir)))
  (ts/make-thinking-selector
   (or levels [:off :low :medium :high])
   (or current :medium)
   (or default :off)
   :on-select on-select
   :on-persist on-persist
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
     "ctrl+c" "\u0003"
     "ctrl+s" "\u0013"
     key)))

(defn- rows
  "The rendered level rows, ANSI-stripped: everything between the search
   input line and the footer hint."
  [sel]
  (->> (mapv u/strip-ansi-codes (protocols/render sel 120))
       (drop-while #(not (str/starts-with? % "> ")))
       (drop 1)
       (remove str/blank?)
       (take-while #(not (str/includes? % "to select")))
       vec))

(t/deftest test-initial-state
  (let [sel (selector)]
    (t/is (= 4 (count (rows sel))) "one row per level")
    (let [current-row (first (filter #(str/includes? % "medium") (rows sel)))]
      (t/is (str/includes? current-row "✓") "the active level is marked ✓")
      (t/is (str/includes? current-row "Moderate reasoning") "description shown"))
    (let [default-row (first (filter #(str/includes? % "off") (rows sel)))]
      (t/is (str/includes? default-row "· default")
            "the settings default carries the · default note")
      (t/is (str/includes? default-row "No reasoning") "off description shown"))
    (t/is (str/includes? (nth (rows sel) 2) "→")
          "the active level is also selected initially (pi preselect)")))

(t/deftest test-arrow-keys-move-selection
  (let [sel (selector)]
    (t/is (str/includes? (nth (rows sel) 2) "→") "initially the current row is selected")
    (press sel "down")
    (t/is (str/includes? (nth (rows sel) 3) "→") "down moves the arrow down")
    (press sel "up")
    (press sel "up")
    (press sel "up")
    (press sel "up")
    (t/is (str/includes? (nth (rows sel) 3) "→") "up wraps to the bottom")
    (press sel "down")
    (t/is (str/includes? (nth (rows sel) 0) "→") "down wraps to the top")))

(t/deftest test-enter-selects
  (let [selected (atom ::none)
        sel (selector :on-select (fn [level] (reset! selected level)))]
    (press sel "enter")
    (t/is (= :medium @selected) "enter selects the current level")
    (press sel "down")
    (press sel "enter")
    (t/is (= :high @selected) "enter selects the moved-to level")))

(t/deftest test-ctrl-s-persists
  (let [persisted (atom ::none)
        sel (selector :on-persist (fn [level] (reset! persisted level)))]
    (press sel "down")
    (press sel "ctrl+s")
    (t/is (= :high @persisted) "Ctrl+S hands the selection to on-persist")))

(t/deftest test-escape-cancels
  (let [cancelled (atom false)
        sel (selector :on-cancel (fn [] (reset! cancelled true)))]
    (press sel "escape")
    (t/is (true? @cancelled))))

(t/deftest test-ctrl-c-clears-search-then-cancels
  (let [cancelled (atom false)
        sel (selector :on-cancel (fn [] (reset! cancelled true)))]
    (doseq [c ["h" "i" "g" "h"]] (press sel c))
    (t/is (= 1 (count (rows sel))) "typing filters the rows")
    (press sel "ctrl+c")
    (t/is (false? @cancelled) "Ctrl+C with a search clears it, not cancel")
    (t/is (= 4 (count (rows sel))) "the search cleared")
    (press sel "ctrl+c")
    (t/is (true? @cancelled) "Ctrl+C with an empty search cancels")))

(t/deftest test-search-filters-then-selects
  (let [selected (atom ::none)
        sel (selector :on-select (fn [level] (reset! selected level)))]
    (doseq [c ["h" "i" "g" "h"]] (press sel c))
    (t/is (= 1 (count (rows sel))) "only high matches \"high\"")
    (t/is (str/includes? (first (rows sel)) "→") "the single filtered row is selected")
    (press sel "enter")
    (t/is (= :high @selected) "enter selects the filtered level")
    ;; clear the filter, then type max
    (press sel "ctrl+c")
    (doseq [c ["m" "a" "x"]] (press sel c))
    (t/is (= 1 (count (rows sel))) "\"max\" filters to max only")))

(t/deftest test-no-match-shows-empty-state
  (let [sel (selector)]
    (press sel "z")
    (let [row (first (rows sel))]
      (t/is (str/includes? row "No matching levels") "empty filter state")))
  (let [selected (atom ::none)
        sel (selector :on-select (fn [level] (reset! selected level)))]
    (press sel "z")
    (press sel "enter")
    (t/is (= ::none @selected) "enter with no matches is a no-op")))

(t/deftest test-navigation-rebuilds-do-not-leak-watches
  ;; Rows re-derive per navigation (pi updateList) — the retired rows must
  ;; be disposed or each keypress leaks a row-set of track! watches.
  (let [sel (selector)
        watchers #(count @(deref #'macros/watch-registry))]
    (core/render sel 60)
    (let [baseline (watchers)]
      (dotimes [_ 6] (press sel "down") (core/render sel 60))
      (t/is (= baseline (watchers))
            "steady state: navigation does not accumulate watches"))))

(t/deftest test-root-body-memoizes-idle-frames
  ;; The state atom is read through tracked-deref: an idle re-render hands
  ;; back the cached tree (0 bodies run) and a state change re-derives once;
  ;; only the rows whose text changed rebuild.
  (let [sel (selector)]
    (protocols/render sel 80)
    (hiccup/reset-counters!)
    (protocols/render sel 80)
    (t/is (zero? (:bodies-run (hiccup/counters))) "idle frame: body cached")
    (t/is (= 1 (:bodies-skipped (hiccup/counters))))
    (hiccup/reset-counters!)
    (press sel "down")
    (protocols/render sel 80)
    (t/is (= 1 (:bodies-run (hiccup/counters))) "state change re-derives once")
    ;; :text carries no patch lens — the two rows whose text changed (the
    ;; arrow moving) rebuild; unchanged rows are reused as-is
    (t/is (= 2 (:constructs (hiccup/counters))))
    (t/is (= 2 (:disposals (hiccup/counters))) "dropped rows are disposed")))

(t/deftest test-thinking-save-and-cancel-resolve-through-the-manager
  (t/testing "app.thinking.save is a real id (pi) — a rebind moves Ctrl+S"
    (let [persisted (atom ::none)
          sel (selector :on-persist (fn [level] (reset! persisted level)))]
      (tui-kb/set-user-bindings! (tui-kb/get-global-keybindings)
                                 {"app.thinking.save" "ctrl+y"})
      (try
        (press sel "ctrl+s")
        (t/is (= ::none @persisted) "ctrl+s was rebound away")
        (press sel "\u0019")                    ;; ctrl+y
        (t/is (= :medium @persisted) "ctrl+y saves the selection")
        (finally
          (tui-kb/set-user-bindings! (tui-kb/get-global-keybindings) {})))))
  (t/testing "escape cancels through tui.select.cancel"
    (let [cancelled (atom false)
          sel (selector :on-cancel (fn [] (reset! cancelled true)))]
      (tui-kb/set-user-bindings! (tui-kb/get-global-keybindings)
                                 {"tui.select.cancel" "ctrl+x"})
      (try
        (press sel "escape")
        (t/is (false? @cancelled) "escape was rebound away")
        (press sel "\u0018")                    ;; ctrl+x
        (t/is (true? @cancelled) "ctrl+x cancels")
        (finally
          (tui-kb/set-user-bindings! (tui-kb/get-global-keybindings) {}))))))

