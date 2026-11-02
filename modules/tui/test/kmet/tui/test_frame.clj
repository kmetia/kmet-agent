(ns kmet.tui.test-frame
  "The frame pass on its own: a stub TUI, a document fn, and the staged
   bytes — no terminal and no render loop. This is what the extraction
   bought: build → diff → emit is callable and assertable directly."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.tui.core :as core]
            [kmet.tui.frame :as frame]))

(defn- make-fixture
  "A TUI over no terminal plus the frame state the loop owns, and a RENDER fn
   that runs one frame of DOC and returns the staged bytes."
  [doc]
  (let [tui (core/create-tui nil)
        out (atom nil)
        render (fn [& [w h]]
                 (reset! out nil)
                 (frame/render-frame! tui
                                      {:width (or w 20)
                                       :height (or h 10)
                                       :compose (fn [_tui _w _h] @doc)
                                       :hardware-cursor-row (atom 0)
                                       :previous-viewport-top (atom 0)
                                       :request-render (fn [& _] nil)
                                       :pending-write out})
                 (some-> @out str))]
    {:tui tui :render render}))

(deftest render-frame-paints-then-diffs
  (let [doc (atom ["alpha" "beta"])
        {:keys [tui render]} (make-fixture doc)]
    (testing "the first frame paints the document without clearing"
      (let [s (render)]
        (is (string? s))
        (is (str/includes? s "alpha"))
        (is (str/includes? s "beta"))
        (is (not (str/includes? s "\u001b[3J"))
            "the first render preserves the terminal output above the TUI")))
    (testing "an unchanged frame re-emits no content"
      (let [s (render)]
        (is (not (str/includes? s "alpha")))
        (is (not (str/includes? s "beta")))))
    (testing "a changed line repaints that line and not the document"
      (swap! doc assoc 1 "BETA")
      (let [s (render)]
        (is (str/includes? s "BETA"))
        (is (not (str/includes? s "alpha")))))
    (testing "a forced render drops the memo and re-emits with a clear"
      (swap! doc assoc 1 "beta")
      (frame/reset-frame! tui)
      (let [s (render)]
        (is (str/includes? s "\u001b[3J") "the scrollback is cleared")
        (is (str/includes? s "alpha"))))))

(deftest render-frame-full-redraws-on-a-width-change
  (let [doc (atom ["alpha" "beta"])
        {:keys [render]} (make-fixture doc)]
    (is (string? (render 20 10)))
    (testing "a width change rebuilds the frame instead of diffing it"
      (let [s (render 21 10)]
        (is (str/includes? s "\u001b[3J"))
        (is (str/includes? s "alpha"))))))
