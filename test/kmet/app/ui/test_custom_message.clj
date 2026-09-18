(ns kmet.app.ui.test-custom-message
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.theme :as theme]
            [kmet.tui.core :as core]
            [kmet.libs.terminal-image :as timg]
            [kmet.app.ui.subs :as subs]
            [kmet.app.ui.custom-message :as cm]
            [kmet.tui.macros :as macros]))

(defn- strip-ansi [s]
  (clojure.string/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(deftest test-create
  (testing "create custom message component"
    (let [c (cm/make-custom-message :label "info" :content "hello")]
      (is (some? c)))))

(deftest test-render-label
  (testing "renders label in brackets"
    (let [c (cm/make-custom-message :label "system" :content "message")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"\[system\]" %) plain)
          "Label should show as [system]"))))

(deftest test-render-content
  (testing "renders content text"
    (let [c (cm/make-custom-message :label "info" :content "Welcome to kmet")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"Welcome to kmet" %) plain)))))

(deftest test-no-label
  (testing "renders without label"
    (let [c (cm/make-custom-message :content "just content")
          plain (mapv strip-ansi (core/render c 40))]
      (is (some #(re-find #"just content" %) plain))
      (is (not-any? #(re-find #"\[" %) plain)
          "No label should mean no brackets"))))

(deftest test-empty-content
  (testing "empty content still renders box padding"
    (let [c (cm/make-custom-message :label "test")]
      ;; Even with empty content, the box padding lines render
      (is (pos? (count (core/render c 40)))))))

(deftest test-label-content-spacer
  (testing "a blank line separates the label from the content (pi: box.addChild(new Spacer(1)))"
    (let [c (cm/make-custom-message :label "Reload" :content "Reloaded.")
          plain (mapv strip-ansi (core/render c 40))
          label-idx (first (keep-indexed #(when (re-find #"\[Reload\]" %2) %1) plain))
          content-idx (first (keep-indexed #(when (re-find #"Reloaded\." %2) %1) plain))]
      (is label-idx)
      (is content-idx)
      (is (= content-idx (+ label-idx 2))
          "label line, one blank bg line, then content — like pi"))))

(deftest test-no-label-no-spacer
  (testing "no label means no spacer line (pi only adds the Spacer(1) after the label Text)"
    (let [c (cm/make-custom-message :content "just content")
          plain (mapv strip-ansi (core/render c 40))
          content-idx (first (keep-indexed #(when (re-find #"just content" %2) %1) plain))]
      (is content-idx)
      (is (= content-idx 2)
          "box top pad, content line — no blank line between them"))))

(deftest test-theme-sub-retheme
  (testing "swapping the shared theme atom re-themes the message on next render (Stage 5)"
    (let [c (cm/make-custom-message :label "test" :content "test")
          _ (core/render c 40)
          before (core/render c 40)]
      (reset! theme/theme-atom (theme/get-theme "light"))
      (try
        (let [after (core/render c 40)]
          (is (= (mapv strip-ansi before) (mapv strip-ansi after))
              "content unchanged across the palette switch")
          (is (not= before after) "styling changed with the theme"))
        (finally
          (reset! theme/theme-atom (theme/get-theme "dark")))))))

(deftest test-output-pad-follows-the-shared-atom
  (testing "the pad lives in an atom: one reset! re-pads the box"
    (let [pad (atom 2)
          c (cm/make-custom-message :label "test" :content "test" :output-pad-atom pad)]
      (is (some #(re-find #"^  \[test\]" %) (mapv strip-ansi (core/render c 40))))
      (reset! pad 4)
      (is (some #(re-find #"^    \[test\]" %) (mapv strip-ansi (core/render c 40)))
          "the next render follows the atom")
      (is (= 4 @(:padding-x-atom @(:box c)))))))

(deftest test-background
  (testing "renders with custom-message-bg background"
    (let [c (cm/make-custom-message :label "info" :content "test")
          lines (core/render c 40)]
        ;; First line is Spacer(1) — no background. Remaining lines (from Box) have bg.
      (is (every? #(re-find #"\u001b\[48" %) (rest lines))
          "Box lines should have background ANSI codes"))))

(deftest test-long-content-wraps
  (testing "long content wraps to fit width"
    (let [c (cm/make-custom-message :label "info"
                                    :content (apply str (repeat 200 "x")))
          lines (core/render c 30)]
      (is (> (count lines) 3) "Long content should wrap to multiple lines"))))

;; ─── Content images (P2: terminal.showImages) ──────────────────────────────

(def ^:private png
  "A 1x1 PNG."
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

(deftest test-images-render-inline
  (testing "content images render inside the message box (text indicator without protocol support)"
    (let [prev-settings @subs/image-settings-atom
          prev-caps (timg/get-capabilities)]
      (try
        (reset! subs/image-settings-atom {:show-images true :image-width-cells 60})
        (timg/set-capabilities! {:images nil :true-color true :hyperlinks true})
        (let [c (cm/make-custom-message :label "ext" :content "hi"
                                        :images [{:data png :mime-type "image/png"}])
              plain (mapv strip-ansi (core/render c 60))]
          (is (some #(re-find #"hi" %) plain))
          (is (some #(re-find #"\[Image: \[image/png\] 1x1\]" %) plain)))
        (finally
          (reset! subs/image-settings-atom prev-settings)
          (timg/set-capabilities! prev-caps))))))

;; ─── Rebuild disposal (zombie-watch guard) ─────────────────────────────────

(deftest test-collapsible-rebuild-no-watch-leak
  (testing "expand/collapse rebuilds dispose the replaced children — watch
            registry stays flat across toggles"
    (let [watchers #(count @(deref #'macros/watch-registry))
          c (cm/make-custom-message :label "ext" :content "collapsed text")]
      (cm/custom-message-set-collapsible-content! c "collapsed text" "expanded text")
      (core/render c 60)
      (let [baseline (watchers)]
        (dotimes [i 6] (cm/custom-message-set-expanded! c (odd? i)) (core/render c 60))
        (is (= baseline (watchers))
            "toggling expansion does not accumulate watches")))))

(deftest test-theme-switch-rebuild-no-watch-leak
  (testing "a theme switch rebuilds the children once and re-caches"
    (let [watchers #(count @(deref #'macros/watch-registry))
          c (cm/make-custom-message :label "ext" :content "note")]
      (core/render c 60)
      (let [baseline (watchers)]
        (reset! theme/theme-atom (theme/get-theme "light"))
        (try
          (core/render c 60)
          (is (= baseline (watchers))
              "the re-themed rebuild disposed the replaced children")
          (finally (reset! theme/theme-atom (theme/get-theme "dark"))))))))

(deftest test-output-pad-patches-in-place
  (testing "a pad change patches the live box — the inner container and
            its children are reused, so nothing is disposed and the watch
            registry does not move"
    (let [watchers #(count @(deref #'macros/watch-registry))
          pad (atom 1)
          c (cm/make-custom-message :label "ext" :content "note"
                                    :output-pad-atom pad)
          old-box @(:box c)
          old-container @(:inner-container c)]
      (core/render c 60)
      (let [old-children (vec @(:children old-container))
            baseline (watchers)]
        (reset! pad 3)
        (is (identical? old-box @(:box c)) "the box was not replaced")
        (is (identical? old-container @(:inner-container c))
            "the container was not replaced")
        (is (= old-children (vec @(:children old-container)))
            "the content children (and their expansion state) survived")
        (core/render c 60)
        (is (= baseline (watchers))
            "an in-place patch touches no watches")
        (is (some #(str/starts-with? (strip-ansi %) "   [ext]")
                  (core/render c 60))
            "the new padding is in the output")))))
