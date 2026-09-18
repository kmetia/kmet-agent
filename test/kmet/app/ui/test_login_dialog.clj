(ns kmet.app.ui.test-login-dialog
  "Login dialog tests — the content tree conversion (dsl.md stage 4, item
   12): show-* mutations are pure row swaps re-derived by the mounted root,
   exactly one input row exists at a time, a resolved prompt becomes a
   `> answer` transcript line, and dispose unwinds the tree's reaction."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.ui.login-dialog :as ld]
            [kmet.tui.hiccup :as h]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]
            [kmet.libs.reakt :as r]))

(defn- strip-ansi [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- render-plain [comp width]
  (->> (protocols/render comp width) (mapv strip-ansi)))

(defn- make-dialog []
  (ld/make-login-dialog nil "TestProv" (fn [_ _] nil)))

(deftest test-initial-frame-and-input-row
  (testing "the dialog renders chrome + title + the initial bare input"
    (let [d (make-dialog)]
      (try
        (let [lines (render-plain d 50)]
          (is (some #(re-find #"Login to TestProv" %) lines) "title shown")
          (is (= 2 (count (filter #(re-find #"─+" %) lines))) "two borders")
          (is (empty? @(:rows-atom d))
              "pi: the Input is not in the tree at construction"))
        (finally (protocols/dispose d))))))

(deftest test-show-auth-replaces-content
  (testing "show-auth! swaps the rows atomically; the input row stays"
    (let [d (make-dialog)]
      (try
        (with-redefs [ld/open-browser (fn [_] nil)]
          (ld/login-dialog-show-auth! d "https://example.com/auth" "Click it"))
        (let [lines (render-plain d 60)]
          (is (some #(re-find #"example\.com/auth" %) lines) "url shown")
          (is (some #(re-find #"Click it" %) lines) "instructions shown"))
        (finally (protocols/dispose d))))))

(deftest test-prompt-transcript-accumulates
  (testing "typed input resolves through handle-input; the answered prompt
            becomes `> answer` and the next prompt appends a fresh input row"
    (let [d (make-dialog)]
      (try
        (let [p (ld/login-dialog-show-prompt! d "First question" nil)]
          (doseq [c "alpha"] (protocols/handle-input d (str c)))
          (protocols/handle-input d "\r")
          (is (= "alpha" @p) "promise delivers the typed value"))
        (ld/login-dialog-show-prompt! d "Second question" nil)
        (let [lines (render-plain d 60)]
          (is (some #(re-find #"> alpha" %) lines) "submitted line shown")
          (is (some #(re-find #"First question" %) lines) "history kept")
          (is (some #(re-find #"Second question" %) lines) "new prompt shown"))
        (is (= 1 (count (filter #(= :input (:row %)) @(:rows-atom d))))
            "exactly one live input row")
        (finally (protocols/dispose d))))))

(deftest test-manual-input-moves-single-input-row
  (testing "show-manual-input! moves (not duplicates) the bare initial input
            row — pi Container.addChild moves semantics"
    (let [d (make-dialog)]
      (try
        (ld/login-dialog-show-manual-input! d "Paste the code")
        (let [rows @(:rows-atom d)]
          (is (= 1 (count (filter #(= :input (:row %)) rows)))
              "single input row")
          (is (= :input (:row (peek (pop rows)))) "input second to last")
          (is (= :text (:row (peek rows))) "hint line last"))
        (finally (protocols/dispose d))))))

(deftest test-appends-follow-pi-content-order
  (testing "show-waiting/progress/info append at the content end — pi keeps
            the Input out of the tree outside an active prompt, so appended
            rows never interact with an input line"
    (let [d (make-dialog)]
      (try
        (ld/login-dialog-show-device-code! d "https://example.dev" "ABC-123")
        (ld/login-dialog-show-waiting! d "Waiting for authentication...")
        (ld/login-dialog-show-progress! d "Polling...")
        (ld/login-dialog-show-info! d "Provider says hi")
        (let [rows @(:rows-atom d)
              texts (mapv :text rows)
              waiting-pos (first (keep-indexed (fn [i t] (when (str/includes? (str t) "Waiting") i)) texts))
              polling-pos (first (keep-indexed (fn [i t] (when (str/includes? (str t) "Polling") i)) texts))
              info-pos (first (keep-indexed (fn [i t] (when (str/includes? (str t) "says hi") i)) texts))]
          (is (not-any? #(= :input (:row %)) rows)
              "no input row: pi only mounts it during prompts")
          (is (< waiting-pos polling-pos info-pos) "appended in call order"))
        ;; a prompt then mounts exactly one input at the end
        (ld/login-dialog-show-manual-input! d "Paste the code")
        (let [rows @(:rows-atom d)]
          (is (= 1 (count (filter #(= :input (:row %)) rows)))
              "single input row while prompting")
          (is (= :input (:row (peek (pop rows)))) "input second to last"))
        (finally (protocols/dispose d))))))

(deftest test-cancel-settles-pending-prompt
  (testing "escape settles the pending promise with the cancellation ex-info
            and fires on-complete (pi cancel)"
    (let [completed (atom nil)
          d (ld/make-login-dialog nil "TestProv"
                                  (fn [ok msg] (reset! completed [ok msg])))]
      (try
        ;; show-prompt! creates the pending promise; escape settles it
        (let [p (ld/login-dialog-show-prompt! d "Enter token" nil)]
          (protocols/handle-input d "\u001b")
          (is (instance? Exception @p) "pending prompt settled with ex-info")
          (is (= [false "Login cancelled"] @completed) "on-complete fired"))
        (finally (protocols/dispose d))))))

(deftest test-border-elements-are-reused-not-rebuilt
  (testing "the two border elements keep their instances across rows passes:
            the :color-fn is created once per dialog, so the props stay
            =-equal and reconcile reuses them — a body-built closure would
            rebuild both (the tag has no :apply) and retire the old ones"
    (let [d (make-dialog)]
      (try
        (h/reset-counters!)
        (render-plain d 40)
        (let [first-render (h/counters)]
          (is (= 3 (:constructs first-render)) "two borders + the title")
          (render-plain d 40)
          (let [idle (h/counters)]
            (is (= (:constructs first-render) (:constructs idle))
                "an idle re-render constructs nothing")
            (is (= (:disposals first-render) (:disposals idle)) "and retires nothing")
            (is (= 1 (- (:bodies-skipped idle) (:bodies-skipped first-render)))
                "the body itself is memoized"))
          (ld/login-dialog-show-info! d "hi")
          (render-plain d 40)
          (let [after (h/counters)]
            (is (zero? (:disposals after))
                "nothing was retired — the borders survived the rows change")
            (is (>= (:reuses after) 3) "borders + title matched their instances")))
        (finally (protocols/dispose d)
                 (h/reset-counters!))))))

(deftest test-dispose-unwinds-reaction  (testing "dispose disposes the root: its reaction dies and later row swaps
            no longer re-derive (the watcher is gone)"
                                          (let [d (make-dialog)]
      ;; first render births the wrapper's reaction
                                            (render-plain d 50)
                                            (protocols/dispose d)
                                            (is (= :disposed (:state (r/reaction-state @(:rx (:root d)))))
                                                "content reaction disposed with the dialog"))))

(deftest test-prompt-field-emphasis-is-data
  (testing "the [:input] emphasis is the dialog's focus flag; a re-prompt that
            moves the field clears its text and keeps it focused"
    (let [d (make-dialog)]
      (try
        (protocols/set-focused! d true)
        (ld/login-dialog-show-prompt! d "Question one" nil)
        (is (some #(str/includes? % u/CURSOR-MARKER) (protocols/render d 60))
            "the field renders its caret while the dialog is focused")
        (doseq [c "abc"] (protocols/handle-input d (str c)))
        (is (some #(str/includes? % "abc") (render-plain d 60)) "typed text shows")
        (protocols/set-focused! d false)
        (is (not-any? #(str/includes? % u/CURSOR-MARKER) (protocols/render d 60))
            "unfocused: no caret")
        (protocols/set-focused! d true)
        ;; the second prompt moves the row (the first value is carried in the
        ;; descriptor, so the clear is a prop change) and re-derives its state
        (ld/login-dialog-show-prompt! d "Question two" nil)
        (let [lines (protocols/render d 60)]
          (is (not-any? #(str/includes? % "abc") lines) "the field is cleared")
          (is (some #(str/includes? % u/CURSOR-MARKER) lines) "and still focused"))
        (finally (protocols/dispose d))))))

(deftest test-escape-cancels-without-a-mounted-field
  (testing "the URL/device-code states mount no input; escape still aborts"
    (let [done (atom nil)
          d (ld/make-login-dialog nil "TestProv" (fn [ok msg] (reset! done [ok msg])))]
      (try
        (with-redefs [ld/open-browser (fn [_] nil)]
          (ld/login-dialog-show-auth! d "https://example.com/auth" nil))
        (protocols/handle-input d "\u001b")
        (is (= [false "Login cancelled"] @done))
        ;; the dialog matches tui.select.cancel itself, so both keys the
        ;; Input would have accepted cancel here (escape and ctrl+c)
        (reset! done nil)
        (protocols/handle-input d "\u0003")
        (is (= [false "Login cancelled"] @done))
        (finally (protocols/dispose d))))))
