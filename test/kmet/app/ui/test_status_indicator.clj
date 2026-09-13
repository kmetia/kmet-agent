(ns kmet.app.ui.test-status-indicator
  "Editor-border status rendering (pi: StatusIndicator.renderInBorder and
   CustomEditor.renderTopBorder): the status text and spinner-only shapes,
   the composed first editor line (plain, scrolled, narrow), and the
   embedded/standalone switch make-status-area drives."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.spinner :as spinner]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as hiccup]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.theme :as theme]
            [kmet.tui.utils :as u]
            [kmet.app.ui.status-indicator :as si]))

(defn- strip-ansi [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- plain
  "Stripped text with every braille animation frame normalized to F — the
   frame advances with the clock, so exact-frame assertions would be
   timing-dependent (they are the same component's spinner)."
  [s]
  (str/replace (strip-ansi s) #"[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]" "F"))

(defn- border-line [indicator width hidden]
  (si/editor-top-border {:indicator indicator
                         :width width
                         :hidden-line-count hidden
                         :rule "─"
                         :border-fn nil}))

;; ─── render-in-border / render-spinner-in-border ───────────────────────────

(deftest test-border-status-text
  (testing "the working indicator's status text drops the standalone indent"
    (let [working (si/make-status-indicator :text "Working")]
      (si/status-indicator-start! working)
      (is (= "F Working" (plain (si/render-in-border working 12))))
      (is (= "F" (plain (si/render-spinner-in-border working 12))))
      (testing "an inactive indicator renders nothing"
        (si/status-indicator-stop! working)
        (is (= "" (si/render-in-border working 12))))))
  (testing "the transient indicators render their labels"
    (let [retry (si/make-retry-status-indicator 1 3 2000)
          compaction (si/make-compaction-status-indicator)
          branch (si/make-branch-summary-status-indicator)]
      (is (re-find #"^F Retrying \(1/3\) in [12]s\.\.\."
                   (plain (si/render-in-border retry 80)))
          "the countdown may tick 2s → 1s while the test runs")
      (is (str/includes? (plain (si/render-in-border compaction 80))
                         "Compacting context..."))
      (is (str/includes? (plain (si/render-in-border branch 80))
                         "Summarizing branch..."))
      (testing "no ellipsis — the border owns the geometry"
        (is (= "F Retr" (plain (si/render-in-border retry 6))))
        (is (= 6 (u/visible-width (si/render-in-border retry 6)))))
      (testing "spinner-only fallbacks carry just the colored frame"
        (is (= "F" (plain (si/render-spinner-in-border retry 80))))
        (is (= "F" (plain (si/render-spinner-in-border compaction 80))))
        (is (= "F" (plain (si/render-spinner-in-border branch 80))))))))

(deftest test-working-indicator-resolves-border-color-dynamically
  (testing "the working line takes the editor border color while embedded and
            the accent/muted pair standalone (pi: showWorkingStatusIndicator)"
    (let [mark (fn [s] (str "«" s "»"))
          working (si/make-status-indicator :text "Working"
                                            :border-color-fn (fn [] mark))]
      (si/status-indicator-start! working)
      (is (= "«F» «Working»" (plain (si/render-in-border working 20))))
      (is (= "«F»" (plain (si/render-spinner-in-border working 20))))))
  (testing "a provider answering nil falls back to the theme colors"
    (let [working (si/make-status-indicator :text "Working"
                                            :border-color-fn (fn [] nil))]
      (si/status-indicator-start! working)
      (is (str/includes? (si/render-in-border working 20)
                         (theme/get-fg-ansi theme/dark-theme :accent))))))

(deftest test-share-spinner-embeds
  (testing "the /share status is a bare spinner, not a status record — it
            must render in the editor border like every other status"
    (let [sp (spinner/make-spinner :text "Creating gist..." :active true)]
      (is (= "F Creating gist..." (plain (si/render-in-border sp 40))))
      (is (= "F" (plain (si/render-spinner-in-border sp 40))))
      (is (= "── F Creating gist... ──────────────────"
             (plain (border-line sp 40 0))))
      (testing "inactive renders nothing (the editor keeps its rule)"
        (spinner/spinner-stop! sp)
        (is (= "" (si/render-in-border sp 40)))
        (is (nil? (border-line sp 40 0)))))))

;; ─── editor-top-border composition ─────────────────────────────────────────

(deftest test-editor-top-border
  (let [working (si/make-status-indicator :text "Working")]
    (si/status-indicator-start! working)
    (testing "nil indicator or empty status → nil (the editor draws its rule)"
      (is (nil? (border-line nil 24 0)))
      (is (nil? (border-line (si/make-status-indicator :text "Working") 24 0))))
    (testing "`── <status> ───...`"
      (is (= "── F Working ───────────" (plain (border-line working 24 0)))))
    (testing "the whole line is exactly the requested width"
      (doseq [width [1 2 3 4 5 10 14 20 40 80]]
        (is (= width (u/visible-width (border-line working width 0))))))
    (testing "scrolled: the centered ↑ label shares the line with the status"
      (let [line (plain (border-line working 40 12))]
        (is (= "── F Working ─ ↑ 12 more ───────────────" line))
        (is (= 40 (u/visible-width (border-line working 40 12))))))
    (testing "scrolled + tight: the label wins over the status text"
      (let [line (plain (border-line working 24 3))]
        (is (= "── F ── ↑ 3 more ───────" line))
        (is (= 24 (u/visible-width (border-line working 24 3))))))
    (testing "the border color fn styles the border runs"
      (let [line (si/editor-top-border {:indicator working :width 24
                                        :hidden-line-count 0 :rule "─"
                                        :border-fn (fn [s] (str "<" s ">"))})]
        (is (str/starts-with? line "<── >"))
        (is (str/includes? (plain line) "F Working< "))))
    (testing "an ascii border set flows through as the rule glyph"
      (is (str/starts-with?
           (si/editor-top-border {:indicator working :width 14 :hidden-line-count 0
                                  :rule "-" :border-fn nil})
           "-- ")))))

(deftest test-editor-embeds-status
  (is (not (si/editor-embeds-status? nil)))
  (is (not (si/editor-embeds-status? (editor/make-editor)))
      "a plain editor has no top-border hook")
  (let [e (editor/make-editor)]
    (editor/editor-set-top-border-fn! e (fn [_] nil))
    (is (si/editor-embeds-status? e)
        "the hook counts even when it declines a given render (the opt-in)")))

(deftest test-editor-first-line-embeds-the-status
  (testing "a wired editor renders the active status in its first line and
            returns to the plain rule when the status clears (the
            interactive-mode top-border hook)"
    (let [working (si/make-status-indicator :text "Working")
          cur (atom nil)
          e (editor/make-editor :height 3)]
      (editor/editor-set-top-border-fn!
       e
       (fn [{:keys [width hidden-line-count rule border-fn]}]
         (si/editor-top-border
          {:indicator (or (:indicator @cur)
                          (when (si/status-indicator-active? working) working))
           :width width :hidden-line-count hidden-line-count
           :rule rule :border-fn border-fn})))
      (is (= (apply str (repeat 30 "─")) (first (core/render e 30))))
      (si/status-indicator-start! working)
      (let [line (plain (first (core/render e 30)))]
        (is (str/starts-with? line "── F Working "))
        (is (= 30 (u/visible-width line)) "still a full-width rule"))
      (testing "a transient indicator replaces the working status"
        (si/status-indicator-stop! working)
        (reset! cur {:kind :retry :indicator (si/make-retry-status-indicator 1 3 2000)})
        (is (str/starts-with? (plain (first (core/render e 40))) "── F Retrying")))
      (testing "clearing restores the plain rule"
        (reset! cur nil)
        (is (= (apply str (repeat 30 "─")) (first (core/render e 30))))))))

;; ─── make-status-area: embedded vs standalone ──────────────────────────────

(deftest test-status-area-switches-on-editor-embedding
  (let [working (si/make-status-indicator :text "Working")
        cur (atom nil)
        ed (atom nil)
        root (hiccup/root (si/make-status-area cur working ed))]
    (testing "no embedding editor → the standalone layer renders"
      (si/status-indicator-start! working)
      (let [lines (protocols/render root 60)]
        (is (= 2 (count lines)))
        (is (some #(str/includes? % "Working") lines))))
    (testing "an embedding editor → the layer renders nothing (the editor
              border carries the status)"
      (let [embed (editor/make-editor)]
        (editor/editor-set-top-border-fn! embed (fn [_] "x"))
        (reset! ed embed)
        (is (= [] (protocols/render root 60)))))
    (testing "swapping back to a plain editor restores the layer"
      (reset! ed (editor/make-editor))
      (is (= 2 (count (protocols/render root 60)))))))
