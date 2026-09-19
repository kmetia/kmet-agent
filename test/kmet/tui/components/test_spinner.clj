(ns kmet.tui.components.test-spinner
  "Spinner tests — pi Loader parity: leading blank line, animated frames,
   and set-indicator! verbatim mode (custom frames rendered as-is, empty
   frames hide the indicator, nil restores defaults)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.tui.core :as core]
            [kmet.tui.components.spinner :as sp]))

(defn- strip-ansi [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn- render-lines [spinner width]
  (mapv strip-ansi (core/render spinner width)))

(deftest test-inactive-renders-nothing
  (testing "inactive spinner renders nothing (pi: Loader not started)"
    (let [sp (sp/make-spinner :text "Working..." :active false)]
      (t/is (= [] (core/render sp 20))))))

(deftest test-active-renders-leading-blank
  (testing "active spinner renders a leading blank line + animated line"
    (let [sp (sp/make-spinner :text "Working..." :active true :prefix "  "
                              :spinner-color-fn identity :message-color-fn identity)]
      (t/is (= 2 (count (render-lines sp 40))))
      (t/is (= "" (first (render-lines sp 40))))
      (t/is (re-find #"Working" (second (render-lines sp 40)))))))

(deftest test-indicator-verbatim-frames
  (testing "set-indicator! renders custom frames verbatim (no color fn)"
    (let [sp (sp/make-spinner :text "msg" :active true :prefix ""
                              :spinner-color-fn (fn [_] "COLORED"))
          ;; a long interval pins the first frame for the whole test — 1ms
          ;; raced the full jolt run (an interruption advanced it to "○")
          _ (sp/spinner-set-indicator! sp {:frames ["●" "○"] :interval-ms 60000})]
      (t/is (re-find #"●" (second (render-lines sp 20)))
            "custom frame appears verbatim"))))

(deftest test-indicator-empty-frames-hide-indicator
  (testing "empty frames hide the indicator and show only the message"
    (let [sp (sp/make-spinner :text "msg" :active true :prefix "")
          _ (sp/spinner-set-indicator! sp {:frames []})]
      (t/is (= "msg" (second (render-lines sp 20)))
            "message-only line, no spinner frame"))))

(deftest test-indicator-nil-restores-defaults
  (testing "nil options restore the default frames and color-fn rendering"
    (let [sp (sp/make-spinner :text "msg" :active true :prefix ""
                              :spinner-color-fn (fn [f] (str "[" f "]")))
          _ (sp/spinner-set-indicator! sp {:frames ["X"]})
          _ (sp/spinner-set-indicator! sp nil)]
      (t/is (re-find #"\[⠋\]" (second (render-lines sp 20)))
            "default frames + color fn rendering restored"))))

(deftest test-indicator-interval-clamp
  (testing "non-positive interval falls back to the default"
    (let [sp (sp/make-spinner :text "msg" :active true :prefix "")
          _ (sp/spinner-set-indicator! sp {:frames ["a" "b"] :interval-ms 0})]
      (t/is (= "a msg" (second (render-lines sp 20)))
            "still renders (default interval), not a division error"))))
