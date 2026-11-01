(ns kmet.ui.tui.test-fork-selector
  "Fork selector list tests (pi UserMessageList parity): two-line rows with
   position metadata, initial selection at the most recent message, wrap-
   around navigation, enter/escape wiring, single-line normalization, and
   the scroll indicator."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing is]]
            [kmet.app.keybindings :as kb]
            [kmet.ui.tui.fork-selector :as fork]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.test-tmp :as test-tmp]))

(defn- install-keybindings! []
  (let [dir (test-tmp/temp-dir! "test-fork-selector-keybindings-")]
    (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir))))

(defn- make-list
  "Build a ForkMessageList over N messages \"msg <i>\". Returns the list
   plus recorders for select/cancel."
  [n]
  (install-keybindings!)
  (let [messages (mapv (fn [i] {:id (str "id-" i) :text (str "msg " i)}) (range n))
        selected (atom nil)
        cancelled (atom false)
        list (fork/map->ForkMessageList
              {:messages-atom (atom messages)
               :selected-idx-atom (atom (dec n))
               :on-select-atom (atom (fn [id] (reset! selected id)))
               :on-cancel-atom (atom (fn [] (reset! cancelled true)))
               :cache-atom (atom nil)})]
    {:list list :selected selected :cancelled cancelled :n n}))

(defn- press [list key]
  (protocols/handle-input
   list
   (case key
     "up" "\u001b[A"
     "down" "\u001b[B"
     "enter" "\r"
     "escape" "\u001b"
     key)))

(defn- render-text [list width]
  (mapv u/strip-ansi-codes (protocols/render list width)))

(defn- cursor-row [list width]
  (some #(when (str/includes? % "›") %) (render-text list width)))

(deftest initial-selection-is-the-most-recent-message
  (testing "pi: default selection is the last (most recent) message"
    (let [{:keys [list]} (make-list 4)]
      (is (str/includes? (cursor-row list 100) "msg 3")))))

(deftest rows-are-two-lines-plus-blank-with-metadata
  (testing "each message renders as message line + position line + blank"
    (let [{:keys [list]} (make-list 2)]
      ;; selection starts on the most recent → its row carries the cursor
      (is (= ["  msg 0"
              "  Message 1 of 2"
              ""
              "› msg 1"
              "  Message 2 of 2"
              ""]
             (render-text list 80))))))

(deftest navigation-wraps-at-both-ends
  (testing "up from the newest wraps to the oldest and vice versa"
    (let [{:keys [list]} (make-list 3)]
      (press list "up") ; idx 2 -> 1
      (is (str/includes? (cursor-row list 100) "msg 1"))
      (press list "up") ; idx 1 -> 0
      (is (str/includes? (cursor-row list 100) "msg 0"))
      (press list "up") ; idx 0 wraps to 2
      (is (str/includes? (cursor-row list 100) "msg 2"))
      (press list "down") ; idx 2 wraps to 0
      (is (str/includes? (cursor-row list 100) "msg 0")))))

(deftest enter-selects-and-escape-cancels
  (testing "enter emits the selected entry id; escape fires cancel"
    (let [{:keys [list selected cancelled]} (make-list 3)]
      (press list "up")
      (press list "enter")
      (is (= "id-1" @selected))
      (is (not @cancelled))
      (press list "escape")
      (is @cancelled))))

(deftest multi-line-messages-normalize-to-one-row
  (testing "newlines become spaces so a message is one row (pi: replace \\n)"
    (install-keybindings!)
    (let [list (fork/map->ForkMessageList
                {:messages-atom (atom [{:id "a" :text "bb run\nWarning: classpath"}])
                 :selected-idx-atom (atom 0)
                 :on-select-atom (atom nil)
                 :on-cancel-atom (atom nil)
                 :cache-atom (atom nil)})]
      (doseq [line (render-text list 80)]
        (is (not (str/includes? line "\n"))))
      (is (some #(str/includes? % "bb run Warning: classpath") (render-text list 80))))))

(deftest scroll-indicator-appears-when-clipped
  (testing "more messages than maxVisible shows the position indicator"
    (let [{:keys [list]} (make-list 15)]
      (is (some #(str/includes? % "(15/15)") (render-text list 80))))))

(deftest empty-list-renders-muted-placeholder-and-ignores-keys
  (testing "no messages → muted placeholder; enter does nothing"
    (install-keybindings!)
    (let [selected (atom :untouched)
          cancelled (atom false)
          list (fork/map->ForkMessageList
                {:messages-atom (atom [])
                 :selected-idx-atom (atom 0)
                 :on-select-atom (atom (fn [id] (reset! selected id)))
                 :on-cancel-atom (atom (fn [] (reset! cancelled true)))
                 :cache-atom (atom nil)})
          ls (render-text list 80)]
      (is (some #(str/includes? % "No user messages found") ls))
      (press list "enter")
      (is (= :untouched @selected))
      (is (not @cancelled)))))
