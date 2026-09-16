(ns kmet.app.ui.test-scoped-models-selector
  "Scoped-models selector tests — enabled-ids helpers and handle-input
   behaviors (toggle on Enter, Ctrl+A/X/P, Alt+Up/Down reorder, Ctrl+S save,
   search filter, cancel)."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as kb]
            [kmet.app.ui.scoped-models-selector :as sms]
            [kmet.ai.models :as models]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.protocols :as protocols]))

(defn- model [provider id]
  (models/map->Model {:id id :name (str "Model " id) :provider provider
                      :api :openai-completions :base-url "https://x"
                      :reasoning false :input [:text]
                      :cost {:input 0 :output 0 :cache-read 0 :cache-write 0}
                      :context-window 1000 :max-tokens 100}))

(defn- selector
  "Build a selector over p1/a p1/b p2/x with global keybindings installed."
  [enabled-ids & {:keys [on-change on-persist on-cancel]}]
  (let [dir (str (fs/create-dirs (fs/path "target" "test-sms-keybindings")))]
    (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir)))
  (sms/make-scoped-models-selector
   [(model :p1 "a") (model :p1 "b") (model :p2 "x")]
   enabled-ids
   :on-change on-change
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
     "ctrl+a" "\u0001"
     "ctrl+c" "\u0003"
     "ctrl+p" "\u0010"
     "ctrl+s" "\u0013"
     "ctrl+x" "\u0018"
     "alt+up" "\u001b\u001b[A"
     "alt+down" "\u001b\u001b[B"
     key)))

;; ─── Enabled-ids helpers ───────────────────────────────────────────────────

(t/deftest test-toggle-helper
  (t/is (= ["p1/a"] (@#'sms/toggle nil "p1/a"))
        "first toggle from null starts with only this id")
  (t/is (= ["p1/a" "p1/b"] (@#'sms/toggle ["p1/a"] "p1/b")))
  (t/is (= ["p1/b"] (@#'sms/toggle ["p1/a" "p1/b"] "p1/a"))))

(t/deftest test-enable-all-helper
  (t/is (nil? (@#'sms/enable-all nil ["a" "b"] nil))
        "null stays null (all enabled)")
  (t/is (nil? (@#'sms/enable-all ["a"] ["a" "b"] nil))
        "all targets enabled collapses to null")
  (t/is (= ["a" "c"] (@#'sms/enable-all ["a"] ["a" "b" "c"] ["c"]))
        "target-ids restricts the enable set"))

(t/deftest test-clear-all-helper
  (t/is (= [] (@#'sms/clear-all ["a" "b"] ["a" "b" "c"] nil))
        "clears the explicit list")
  (t/is (= ["c"] (@#'sms/clear-all ["a" "b" "c"] ["a" "b" "c"] ["a" "b"]))
        "target-ids clears only those")
  (t/is (= ["c"] (@#'sms/clear-all nil ["a" "b" "c"] ["a" "b"]))
        "from null keeps all but the targets"))

(t/deftest test-move-helper
  (t/is (= ["b" "a"] (@#'sms/move ["a" "b"] "a" 1)))
  (t/is (= ["a" "b"] (@#'sms/move ["a" "b"] "a" -1)) "out of bounds: unchanged")
  (t/is (= ["a" "b"] (@#'sms/move ["a" "b"] "z" 1)) "unknown id: unchanged")
  (t/is (nil? (@#'sms/move nil "a" 1)) "null stays null"))

(t/deftest test-sorted-ids-helper
  (t/is (= ["b" "a" "c"] (@#'sms/sorted-ids ["b"] ["a" "b" "c"]))
        "enabled first (in order), then the rest")
  (t/is (= ["a" "b"] (@#'sms/sorted-ids nil ["a" "b"]))))

;; ─── handle-input behaviors ────────────────────────────────────────────────

(t/deftest test-arrow-keys-move-selection
  ;; navigation must rebuild the rows (pi updateList), not just move the
  ;; state — the rendered selection arrow follows the selection
  (let [sel (selector nil)
        row (fn [i] @(:text-atom (nth @(:children (:rows-container sel)) i)))]
    (t/is (str/includes? (row 0) "→") "initially the first row is selected")
    (press sel "down")
    (t/is (str/includes? (row 1) "→") "down moves the arrow to the next row")
    (t/is (not (str/includes? (row 0) "→")) "and off the previous row")
    (press sel "up")
    (t/is (str/includes? (row 0) "→") "up moves the arrow back")
    (press sel "down")
    (press sel "down")
    (press sel "down")
    (t/is (str/includes? (row 0) "→") "down wraps to the top at the bottom")))

(t/deftest test-enter-toggles-selected
  (let [changed (atom ::none)
        sel (selector nil :on-change (fn [ids] (reset! changed ids)))]
    (press sel "enter")
    (t/is (= ["p1/a"] @changed) "first toggle enables only the selected")
    (press sel "enter")
    (t/is (= [] @changed) "toggle off removes it")))

(t/deftest test-ctrl-a-enables-all
  (let [changed (atom ::none)
        sel (selector ["p1/a"] :on-change (fn [ids] (reset! changed ids)))]
    (press sel "ctrl+a")
    (t/is (nil? @changed) "all enabled collapses to null")))

(t/deftest test-ctrl-x-clears-all
  (let [changed (atom ::none)]
    (let [sel (selector ["p1/a" "p1/b"] :on-change (fn [ids] (reset! changed ids)))]
      (press sel "ctrl+x")
      (t/is (= [] @changed) "clear all"))
    (let [sel (selector nil :on-change (fn [ids] (reset! changed ids)))]
      (press sel "ctrl+x")
      (t/is (= [] @changed) "clear all from all-enabled"))))

(t/deftest test-ctrl-p-toggles-provider
  (let [changed (atom ::none)
        sel (selector ["p2/x"] :on-change (fn [ids] (reset! changed ids)))]
    ;; selection is at 0 = p2/x; toggle its provider (p2 has only x)
    (press sel "ctrl+p")
    (t/is (= [] @changed) "p2 models were enabled → cleared")
    ;; after clearing, the list is [p1/a p1/b p2/x]; move back to p2/x
    (press sel "down")
    (press sel "down")
    (press sel "ctrl+p")
    (t/is (= ["p2/x"] @changed) "cleared → enabled again")))

(t/deftest test-alt-up-down-reorder
  (let [changed (atom ::none)
        sel (selector ["p1/a" "p2/x"] :on-change (fn [ids] (reset! changed ids)))]
    ;; move selection down to p2/x, then up
    (press sel "down")
    (press sel "alt+up")
    (t/is (= ["p2/x" "p1/a"] @changed) "alt+up moves the enabled entry up")))

(t/deftest test-alt-up-at-top-is-a-no-op
  ;; pi: reorder only moves within bounds — an out-of-bounds alt+up must not
  ;; dirty the list or move the selection (the entry is already first)
  (let [changed (atom ::none)
        sel (selector ["p1/a" "p2/x"] :on-change (fn [ids] (reset! changed ids)))]
    (press sel "alt+up")
    (t/is (= ::none @changed) "no change callback")
    (t/is (false? (:dirty @(:state-atom sel))) "not marked unsaved")
    (t/is (= 0 (:selected-idx @(:state-atom sel))) "selection stays on the first row")
    ;; the mirror case: the last enabled row cannot move down either
    (press sel "down")
    (press sel "alt+down")
    (t/is (= ::none @changed) "no change callback at the bottom")
    (t/is (false? (:dirty @(:state-atom sel))) "still not unsaved")))

(t/deftest test-ctrl-s-persists
  (let [persisted (atom ::none)
        sel (selector ["p1/a"] :on-persist (fn [ids] (reset! persisted ids)))]
    (press sel "ctrl+s")
    (t/is (= ["p1/a"] @persisted) "Ctrl+S hands the enabled list to on-persist")))

(t/deftest test-escape-cancels
  (let [cancelled (atom false)
        sel (selector nil :on-cancel (fn [] (reset! cancelled true)))]
    (press sel "escape")
    (t/is (true? @cancelled))))

(t/deftest test-ctrl-c-clears-search-then-cancels
  (let [cancelled (atom false)
        sel (selector nil :on-cancel (fn [] (reset! cancelled true)))]
    (press sel "a")
    (press sel "ctrl+c")
    (t/is (false? @cancelled) "Ctrl+C with a search clears it, not cancel")
    (press sel "ctrl+c")
    (t/is (true? @cancelled) "Ctrl+C with an empty search cancels")))

(t/deftest test-search-filters-then-toggles
  (let [changed (atom ::none)
        sel (selector nil :on-change (fn [ids] (reset! changed ids)))]
    (press sel "p")
    (press sel "2")
    ;; only p2/x is visible now; enter toggles it
    (press sel "enter")
    (t/is (= ["p2/x"] @changed) "search narrowed the visible set")))

(t/deftest test-unavailable-rows-survive
  ;; enabled ids not in the catalog render as unavailable but stay enabled
  (let [changed (atom ::none)
        sel (selector ["ghost/id" "p1/a"] :on-change (fn [ids] (reset! changed ids)))]
    ;; list order: ghost/id first (enabled), then p1/a — move to p1/a and
    ;; toggle it off; the unavailable entry survives untouched
    (press sel "down")
    (press sel "enter")
    (t/is (= ["ghost/id"] @changed) "available row removed, unavailable kept")))

(t/deftest test-model-info-lines
  ;; The selected model's info lines: the name line plus the cost line,
  ;; the rates marked with the footer's direction marks (↑ in · ↓ out ·
  ;; C↑ cache read · C↓ cache write); unpriced models render
  ;; "Cost: free", unavailable rows render the name line only
  (let [priced (assoc (model :p1 "a")
                      :cost {:input 0.44 :output 1.5 :cache-read 0.11 :cache-write 3.3})
        dir (str (fs/create-dirs (fs/path "target" "test-sms-keybindings")))
        _ (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir))
        sel (sms/make-scoped-models-selector
             [priced (model :p1 "b") (model :p2 "x")]
             nil)
        text (fn [i] @(:text-atom (nth @(:children (:rows-container sel)) i)))]
    (t/is (str/includes? (text 4) "Model Name: Model a")
          "the name line under the rows")
    (t/is (str/includes? (text 5) "↑$0.44") "input rate marked ↑")
    (t/is (str/includes? (text 5) "↓$1.5") "output rate marked ↓")
    (t/is (str/includes? (text 5) "C↑$0.11") "cache read marked C↑")
    (t/is (str/includes? (text 5) "C↓$3.3") "cache write marked C↓")
    (press sel "down")
    (t/is (str/includes? (text 5) "Cost: free")
          "unpriced model renders the free line")
    (let [ghost (sms/make-scoped-models-selector [] ["ghost/id"])
          kids @(:children (:rows-container ghost))]
      (t/is (= (count kids) 3)
            "unavailable row renders the name line only, no cost line")
      (t/is (str/includes? @(:text-atom (nth kids 2)) "Model unavailable")
            "marked unavailable"))))

