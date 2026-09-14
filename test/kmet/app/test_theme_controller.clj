(ns kmet.app.test-theme-controller
  "ThemeController tests (pi: InteractiveThemeController) — theme switching,
   auto light/dark sync state, and the on-changed notification."
  (:require [babashka.fs :as fs]
            [clojure.test :as t]
            [clojure.string :as str]
            [kmet.config :as cfg]
            [kmet.tui.core :as core]
            [kmet.tui.terminal :as term]
            [kmet.tui.theme :as theme]
            [kmet.app.theme-controller :as tc]))

(defn- recording-terminal
  "ITerminal stub recording written output."
  []
  (let [writes (atom [])]
    {:terminal (reify term/ITerminal
                 (start! [_ _ _] nil)
                 (stop! [_] nil)
                 (started? [_] true)
                 (write-output [_ s] (swap! writes conj s))
                 (read-input [_ _] -1)
                 (columns [_] 80)
                 (rows [_] 24)
                 (set-progress! [_ _] nil))
     :writes writes}))

(defn- restore-theme-state!
  []
  (theme/on-theme-change nil)
  (theme/set-theme-instance! theme/dark-theme)
  (theme/init-theme! "dark"))

(t/use-fixtures :each (fn [f] (f) (restore-theme-state!)))

(defn- make-ctrl
  "Controller over a stub TUI; captures on-changed invocations."
  [config]
  (let [{:keys [terminal writes]} (recording-terminal)
        tui (core/create-tui terminal)
        changed (atom 0)]
    {:tui tui
     :writes writes
     :changed changed
     :ctrl (tc/make-theme-controller tui config
                                     (fn [_])  ;; show-error
                                     (fn [] (swap! changed inc)))}))

(t/deftest test-constructor-applies-config-theme
  (t/testing "the :theme config setting is applied at construction"
    (let [{:keys [ctrl]} (make-ctrl {:theme "light"})]
      (t/is (= "light" (:name (theme/get-current-theme))))
      (t/is (= "light" (tc/get-active-theme-name ctrl)))))
  (t/testing "the watcher dir is the agent-dir themes root (fixed auto root)"
    (make-ctrl {:theme "dark"})
    (t/is (= (str (fs/path (cfg/get-agent-dir) "themes"))
             @@#'theme/custom-themes-dir))))

(t/deftest test-set-theme-name
  (t/testing "switching themes updates state and notifies"
    (let [{:keys [ctrl changed tui]} (make-ctrl {:theme "dark"})]
      (reset! changed 0)
      (let [result (tc/set-theme-name! ctrl "light")]
        (t/is (true? (:success result)))
        (t/is (= "light" (:name (theme/get-current-theme))))
        (t/is (= "light" (tc/get-active-theme-name ctrl)))
        (t/is (true? @(:force-redraw? tui))
              "a theme switch forces the clearing rebuild — an ordinary diff
              would clamp at the header and leave the scrollback themed dark")
        (t/is (= 2 @changed)
              "notified twice — pi parity: setTheme fires the onThemeChange
              callback and applyThemeName fires notifyChanged"))))
  (t/testing "unknown names fall back to dark and report the error"
    (let [{:keys [ctrl changed]} (make-ctrl {:theme "dark"})]
      (reset! changed 0)
      (let [result (tc/set-theme-name! ctrl "no-such-theme")]
        (t/is (false? (:success result)))
        (t/is (str/includes? (:error result) "Theme not found"))
        (t/is (= "dark" (tc/get-active-theme-name ctrl)))
        (t/is (= 1 @changed) "the fallback still notifies")))))

(t/deftest test-set-theme-instance
  (t/testing "in-memory instances bypass the registry"
    (let [{:keys [ctrl tui]} (make-ctrl {:theme "dark"})]
      (tc/set-theme-instance! ctrl theme/light-theme)
      (t/is (identical? theme/light-theme (theme/get-current-theme)))
      (t/is (= "<in-memory>" (tc/get-active-theme-name ctrl)))
      (t/is (true? @(:force-redraw? tui)) "an instance swap forces the rebuild too"))))

(t/deftest test-get-terminal-theme
  (t/testing "the env-detected terminal theme is exposed"
    (let [{:keys [ctrl]} (make-ctrl {:theme "dark"})]
      (t/is (contains? #{:dark :light} (tc/get-terminal-theme ctrl))))))

(t/deftest test-apply-from-settings-explicit
  (t/testing "an explicit setting is applied (deterministic)"
    (let [{:keys [ctrl]} (make-ctrl {:theme "light"})]
      (tc/apply-from-settings! ctrl)
      (t/is (= "light" (:name (theme/get-current-theme))))
      (t/is (false? @(:auto-sync-enabled-atom ctrl))
            "explicit setting disables auto-sync"))))

(t/deftest test-apply-from-settings-noop-does-not-force
  (t/testing "re-applying the already-active theme is a no-op — no forced
            clearing redraw (every startup runs apply-from-settings!, which
            resolves the theme the constructor already applied; forcing here
            repainted the resumed transcript a second time and cleared the
            scrollback for a theme that never changed)"
    (let [{:keys [ctrl tui changed]} (make-ctrl {:theme "dark"})]
      (reset! changed 0)
      (reset! (:force-redraw? tui) false)
      (tc/apply-from-settings! ctrl)
      (t/is (= "dark" (:name (theme/get-current-theme))))
      (t/is (= "dark" (tc/get-active-theme-name ctrl))
            "the active-name atom is synced even on the no-op path")
      (t/is (false? @(:force-redraw? tui)) "no forced redraw")
      (t/is (zero? @changed) "no on-changed notification"))))

(t/deftest ^:slow test-apply-from-settings-detected-noop-does-not-force
  (t/testing "the no-setting detection path is a no-op when the detected theme
            matches the env-detected theme the constructor applied"
    (let [{:keys [ctrl tui changed]} (make-ctrl {})]
      (reset! changed 0)
      (reset! (:force-redraw? tui) false)
      (tc/apply-from-settings! ctrl)
      (t/is (contains? #{"light" "dark"} (tc/get-active-theme-name ctrl)))
      (t/is (false? @(:force-redraw? tui))
            "the second startup paint (the session-load double redraw) is gone")
      (t/is (zero? @changed) "no on-changed notification"))))

(t/deftest test-apply-theme-name-keyword-detection
  (t/testing "the detection path yields :light/:dark keywords — they must resolve to the
            bare theme name: set-theme! looks up (str name), and (str :light) is \":light\",
            a registry miss that silently fell back to dark"
    (let [{:keys [ctrl changed]} (make-ctrl {:theme "dark"})]
      (reset! changed 0)
      (let [result (#'tc/apply-theme-name! ctrl :light false)]
        (t/is (true? (:success result)) "the keyword resolves to the registered theme")
        (t/is (= "light" (:name (theme/get-current-theme))))
        (t/is (= "light" (tc/get-active-theme-name ctrl))
              "the stored active name is the bare string")
        (t/is (= 2 @changed)
              "a real switch notifies twice — pi parity: setTheme fires the
              onThemeChange callback and applyThemeName fires notifyChanged")))))

(t/deftest ^:slow test-apply-from-settings-auto
  (t/testing "an auto setting enables auto-sync and applies one side; the
            notification sequence is written (CSI ? 2031 h)"
    (let [{:keys [tui ctrl writes]} (make-ctrl {:theme "light/dark"})]
      ;; notifications are written only while the TUI is running
      (reset! (:running? tui) true)
      (tc/apply-from-settings! ctrl)
      ;; detection falls back to the environment on the stub (no OSC 11
      ;; response) — one of the two sides must be active
      (t/is (contains? #{"light" "dark"} (tc/get-active-theme-name ctrl)))
      (t/is (true? @(:auto-sync-enabled-atom ctrl)) "auto-sync enabled")
      (t/is (some #(str/includes? % "\u001b[?2031h") @writes)
            "color-scheme notifications requested"))))

(t/deftest ^:slow test-auto-sync-toggles-on-scheme-report
  (t/testing "a color scheme report switches themes while auto-sync is on"
    (let [{:keys [ctrl]} (make-ctrl {:theme "light/dark"})]
      (tc/apply-from-settings! ctrl)
      (let [before (tc/get-active-theme-name ctrl)
            _ (tc/apply-terminal-theme! ctrl (if (= before "light") :dark :light))]
        (t/is (not= before (tc/get-active-theme-name ctrl))
              "the other side of the auto setting becomes active"))))
  (t/testing "reports are ignored while auto-sync is off"
    (let [{:keys [ctrl]} (make-ctrl {:theme "dark"})]
      (tc/apply-from-settings! ctrl)
      (let [before (tc/get-active-theme-name ctrl)]
        (tc/apply-terminal-theme! ctrl :light)
        (t/is (= before (tc/get-active-theme-name ctrl)) "no switch")))))
