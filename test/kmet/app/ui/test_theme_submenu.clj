(ns kmet.app.ui.test-theme-submenu
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.ui.theme-submenu :as theme-submenu]
            [kmet.tui.core :as core]))

(def ^:const K-DOWN "\u001b[B")
(def ^:const K-UP "\u001b[A")
(def ^:const K-ESC "\u001b")
(def ^:const K-ENTER "\r")

(defn- plain [comp width]
  (mapv #(str/replace % #"\u001b\[[0-9;]*[a-zA-Z]" "") (core/render comp width)))

(defn- rendered [comp]
  (str/join "\n" (plain comp 100)))

(defn- make
  "A theme submenu over [\"dark\" \"light\"] with recording callbacks. DONE
   from the SettingsList contract. Returns {:sub s :commits a :previews a}."
  [current-setting terminal-theme]
  (let [commits (atom [])
        previews (atom [])
        sub (theme-submenu/make-theme-submenu
             current-setting terminal-theme ["dark" "light"]
             (fn [& [value]] (swap! commits conj value))
             :on-preview (fn [v] (swap! previews conj v)))]
    {:sub sub :commits commits :previews previews}))

(deftest theme-submenu-single-mode
  (let [{:keys [sub commits previews]} (make "dark" :dark)]
    (testing "lists Automatic + themes with the current one check-marked"
      (let [text (rendered sub)]
        (is (str/includes? text "Theme"))
        (is (str/includes? text "Automatic"))
        (is (str/includes? text "✓ dark"))
        (is (not (str/includes? text "Automatic Theme")))))
    (testing "moving the selection to Automatic previews the automatic setting"
      (core/handle-input sub K-UP)
      (is (= ["dark/dark"] @previews)))
    (testing "selecting Automatic switches to the automatic menu"
      (core/handle-input sub K-ENTER)
      (let [text (rendered sub)]
        (is (str/includes? text "Automatic Theme"))
        (is (str/includes? text "Light theme"))
        (is (str/includes? text "Dark theme")))
      (is (= ["dark/dark" "dark/dark"] @previews))
      (is (empty? @commits) "switching modes commits nothing"))))

(deftest theme-submenu-single-commit
  (let [{:keys [sub commits]} (make "dark" :dark)]
    (testing "selecting a theme commits it (pi: apply → onDone(value))"
      (core/handle-input sub K-DOWN)  ;; dark → light
      (core/handle-input sub K-ENTER)
      (is (= ["light"] @commits)))))

(deftest theme-submenu-single-escape-restores
  (let [{:keys [sub commits previews]} (make "dark" :dark)]
    (core/handle-input sub K-DOWN)  ;; preview light
    (core/handle-input sub K-ESC)   ;; cancel → preview the original
    (is (= ["light" "dark"] @previews))
    (is (= [nil] @commits) "cancel closes without a change")))

(deftest theme-submenu-automatic-apply
  (let [{:keys [sub commits]} (make "dark" :dark)]
    (core/handle-input sub K-UP)     ;; Automatic
    (core/handle-input sub K-ENTER)  ;; automatic menu
    (core/handle-input sub K-DOWN)   ;; Dark theme
    (core/handle-input sub K-DOWN)   ;; Apply
    (core/handle-input sub K-ENTER)
    (is (= ["dark/dark"] @commits)
        "Apply commits the light/dark setting")))

(deftest theme-submenu-automatic-starts-from-auto-setting
  (let [{:keys [sub commits previews]} (make "light/dark" :light)]
    (testing "an automatic setting opens the automatic menu directly"
      (is (str/includes? (rendered sub) "Automatic Theme")))
    (testing "Apply commits the setting unchanged"
      (core/handle-input sub K-DOWN)  ;; Dark theme
      (core/handle-input sub K-DOWN)  ;; Apply
      (core/handle-input sub K-ENTER)
      (is (= ["light/dark"] @commits)))
    (is (empty? @previews) "no previews unless a picker moves")))

(deftest theme-submenu-light-picker
  (let [{:keys [sub commits previews]} (make "dark" :dark)]
    (core/handle-input sub K-UP)     ;; Automatic
    (core/handle-input sub K-ENTER)  ;; automatic menu
    (testing "the Light theme row opens the theme select"
      (core/handle-input sub K-ENTER)
      (is (str/includes? (rendered sub) "Light Theme")))
    (testing "picking a theme previews and updates the row"
      (core/handle-input sub K-DOWN)   ;; dark → light (selection preview)
      (core/handle-input sub K-ENTER)  ;; commit through the sub-submenu
      (is (some #{"light"} @previews) "highlighting previews the theme name")
      (is (= "light/dark" (last @previews))
          "committing previews the automatic setting (pi: getThemeSetting)")
      (is (not (str/includes? (rendered sub) "Light Theme"))
          "the picker closes back to the automatic menu")
      (is (str/includes? (rendered sub) "Automatic Theme"))
      (is (empty? @commits) "a light/dark pick commits nothing until Apply"))
    (testing "Apply after a pick commits the pair"
      (core/handle-input sub K-DOWN)   ;; Dark theme
      (core/handle-input sub K-DOWN)   ;; Apply
      (core/handle-input sub K-ENTER)
      (is (= ["light/dark"] @commits)))))

(deftest theme-submenu-picker-escape
  (let [{:keys [sub commits previews]} (make "dark" :dark)]
    (core/handle-input sub K-UP)     ;; Automatic
    (core/handle-input sub K-ENTER)  ;; automatic menu
    (core/handle-input sub K-ENTER)  ;; open Light theme picker
    (core/handle-input sub K-DOWN)   ;; highlight light (preview)
    (core/handle-input sub K-ESC)    ;; back: preview the current setting
    (is (= "dark/dark" (last @previews)))
    (is (str/includes? (rendered sub) "Automatic Theme") "back in the menu")
    (is (empty? @commits))))

(deftest theme-submenu-automatic-escape-cancels
  (let [{:keys [sub commits previews]} (make "light/dark" :light)]
    (core/handle-input sub K-ESC)
    (is (= ["light/dark"] @previews) "escape restores the original setting")
    (is (= [nil] @commits) "escape closes without a change")))

(deftest theme-submenu-change-mode-back
  (let [{:keys [sub commits previews]} (make "dark" :dark)]
    (core/handle-input sub K-UP)     ;; Automatic
    (core/handle-input sub K-ENTER)  ;; automatic menu
    (core/handle-input sub K-DOWN)   ;; Dark theme
    (core/handle-input sub K-DOWN)   ;; Apply
    (core/handle-input sub K-DOWN)   ;; Change mode
    (core/handle-input sub K-ENTER)
    (is (str/includes? (rendered sub) "Automatic")
        "Change mode switches back to the single menu")
    (is (= "dark" (last @previews)) "the active automatic theme is previewed")
    (is (empty? @commits))))
