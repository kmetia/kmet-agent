(ns kmet.app.ui.test-dialogs
  "Dialog component tests — selector and input framing and IFocusable
   propagation (pi: ExtensionSelector/InputComponent)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.theme :as theme]
            [kmet.app.ui.dialogs :as d]))

(defn- strip-ansi [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- render-plain [comp width]
  (->> (core/render comp width) (mapv strip-ansi)))

(deftest test-selector-framing
  (testing "selector renders title, options, and border lines"
    (let [comp (d/make-selector-dialog "Pick one" ["A" "B"]
                                       (fn [_] nil) (fn []) theme/dark-theme)
          lines (render-plain comp 40)]
      (t/is (some #(re-find #"Pick one" %) lines) "title shown")
      (t/is (some #(re-find #"A" %) lines) "option A shown")
      (t/is (some #(re-find #"B" %) lines) "option B shown")
      (t/is (some #(re-find #"─+" %) lines) "border line shown"))))

(deftest test-selector-select-and-cancel
  (testing "selecting fires on-select with the option; escape fires on-cancel"
    (let [selected (atom nil)
          cancelled (atom 0)
          comp (d/make-selector-dialog "Pick" ["X" "Y"]
                                       #(reset! selected %)
                                       #(swap! cancelled inc)
                                       theme/dark-theme)]
      (core/handle-input comp "\u001b[B")  ;; down
      (core/handle-input comp "\r")        ;; enter
      (t/is (= "Y" @selected) "enter selects the highlighted option")
      (core/handle-input comp "\u001b")    ;; escape
      (t/is (= 1 @cancelled) "escape cancels"))))

(deftest test-input-dialog-focus-propagation
  (testing "InputDialog implements IFocusable and forwards to the
            inner Input (pi: Focusable propagation for IME)"
    (let [comp (d/make-input-dialog "Name" (fn [_] nil) (fn []) theme/dark-theme)]
      (t/is (satisfies? core/IFocusable comp))
      (t/is (false? (core/focused comp)))
      (core/set-focused! comp true)
      (t/is (true? (core/focused comp)))
      (t/is (true? (core/focused (:input-comp comp)))
            "inner input receives the focus flag"))))

(deftest test-input-dialog-submit
  (testing "typing + enter delivers the value"
    (let [result (atom nil)
          comp (d/make-input-dialog "Name" #(reset! result %) (fn []) theme/dark-theme)]
      (core/set-focused! comp true)
      (doseq [c "abc"] (core/handle-input comp (str c)))
      (core/handle-input comp "\r")
      (t/is (= "abc" @result)))))

(deftest test-selector-hint-keys
  (testing "the frame renders the dialog's keybinding hint (pi: keyHint line)"
    (let [lines (render-plain (d/make-selector-dialog "Pick" ["A" "B"]
                                                      (fn [_] nil) (fn [])
                                                      theme/dark-theme)
                              120)]
      (t/is (some #(re-find #"navigate" %) lines))
      (t/is (some #(re-find #"select" %) lines))
      (t/is (some #(re-find #"cancel" %) lines)))))

(deftest test-input-dialog-hint-keys
  (testing "the input dialog hints submit/cancel (pi: ExtensionInputComponent)"
    (let [lines (render-plain (d/make-input-dialog "Name" (fn [_] nil) (fn [])
                                                   theme/dark-theme)
                              120)]
      (t/is (some #(re-find #"submit" %) lines))
      (t/is (some #(re-find #"cancel" %) lines)))))
