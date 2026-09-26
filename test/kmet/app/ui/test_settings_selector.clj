(ns kmet.app.ui.test-settings-selector
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.ui.chat-history :as chat-history]
            [kmet.app.theme-controller :as theme-ctrl]
            [kmet.app.ui.settings-selector :as ss]
            [kmet.app.ui.subs :as subs]
            [kmet.config :as cfg]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.components.settings-list :as settings-list]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as h]
            [kmet.tui.theme :as th]))

(defn- with-image-env
  "Run F with the shared image settings and terminal capabilities bound
   (both are process-global); restore afterwards."
  [settings caps f]
  (let [prev-settings @subs/image-settings-atom
        prev-caps (timg/get-capabilities)]
    (try
      (reset! subs/image-settings-atom settings)
      (timg/set-capabilities! caps)
      (f)
      (finally
        (reset! subs/image-settings-atom prev-settings)
        (timg/set-capabilities! prev-caps)))))

(deftest image-rows-gated-on-terminal-support
  (testing "no image rows on a terminal without image support (pi: supportsImages)"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images nil :true-color true :hyperlinks true}
      (fn []
        (is (nil? (#'ss/image-rows))))))
  (testing "rows appear when the terminal supports images"
    (with-image-env
      {:show-images true :image-width-cells 60}
      {:images :kitty :true-color true :hyperlinks true}
      (fn []
        (is (= 2 (count (#'ss/image-rows))))))))

(deftest image-rows-reflect-live-settings
  (testing "the rows carry the live settings values and pi's width choices"
    (with-image-env
      {:show-images false :image-width-cells 80}
      {:images :kitty :true-color true :hyperlinks true}
      (fn []
        (is (= [{:id :show-images
                 :label "Show images"
                 :description "Render images inline in terminal"
                 :value "false"
                 :values ["true" "false"]}
                {:id :image-width-cells
                 :label "Image width"
                 :description "Preferred inline image width in terminal cells"
                 :value "80"
                 :values ["60" "80" "120"]}]
               (#'ss/image-rows)))))))

;; ─── The panel itself (show-settings) ──────────────────────────────────────

(defn- settings-cs
  "A minimal CS stand-in: the panel reads :agent-state, :config,
   :chat-history, :dock-current and :current-editor-atom; :tui stays nil
   (the panel guards the rows that need one)."
  []
  {:tui nil
   :agent-state (atom {:provider (atom :anthropic)
                       :model (atom "does-not-exist")
                       :thinking (atom :off)
                       :cfg (atom {:auto-compact true
                                   :block-images false
                                   :steering-mode :one-at-a-time
                                   :follow-up-mode :one-at-a-time
                                   :loop-guard-enabled false
                                   :loop-guard-threshold 3
                                   :thinking-loop-guard-enabled false
                                   :max-retries 0
                                   :base-delay-ms 1000})})
   :config {}
   :chat-history (chat-history/make-chat-history)
   :dock-current (atom nil)
   :current-editor-atom (atom (editor/make-editor))})

(defn- plain [comp width]
  (mapv #(str/replace % #"\u001b\[[0-9;]*[a-zA-Z]" "") (core/render comp width)))

(deftest settings-panel-mounts-the-framed-list
  (testing "show-settings docks the frame and hands focus to the settings list
            (pi: showSelector) — the list is what input goes to"
    (let [cs (settings-cs)
          focused (atom nil)
          renders (atom 0)]
      (with-redefs [core/tui-set-focus (fn [_ c] (reset! focused c))
                    core/tui-request-render (fn [_] (swap! renders inc))]
        (ss/show-settings cs)
        (let [frame (:component @(:dock-current cs))
              sl @focused]
          (is (some? frame) "the frame is docked")
          (is (and (some? (:items-atom sl)) (some? (:max-visible sl)))
              "the settings list took focus")
          (is (pos? @renders))
          (is (some #(str/includes? % "Auto-compact") (plain frame 100)))
          (testing "typing filters the rows, clearing them brings them back"
            (doseq [c "block"] (core/handle-input sl (str c)))
            (let [filtered (plain frame 100)]
              (is (not-any? #(str/includes? % "Auto-compact") filtered))
              (is (some #(str/includes? % "Block images") filtered)))
            (doseq [_ (range 5)] (core/handle-input sl "\u007f"))
            (is (some #(str/includes? % "Auto-compact") (plain frame 100))))
          (testing "escape unwinds the panel: dock restored, frame disposed"
            (let [disposed (atom nil)
                  orig h/dispose-tree!]
              (with-redefs [h/dispose-tree! (fn [f] (reset! disposed f) (orig f))]
                (core/handle-input sl "\u001b"))
              (is (nil? @(:dock-current cs)) "the editor is restored")
              (is (identical? frame @disposed) "the frame's tree is disposed"))))))))

;; ─── New rows (terminal progress / clear-on-shrink / skill commands) ────────

(deftest settings-panel-new-rows-without-tui
  (testing "terminal-progress and skill-commands render without a live tui"
    (let [cs (settings-cs)
          focused (atom nil)]
      (with-redefs [core/tui-set-focus (fn [_ c] (reset! focused c))
                    core/tui-request-render (fn [_] nil)]
        (ss/show-settings cs)
        (let [ids (mapv :id @(:items-atom @focused))]
          (is (some #{:skill-commands} ids))
          (is (some #{:terminal-progress} ids))
          (is (not-any? #{:clear-on-shrink} ids)
              "clear-on-shrink needs the live tui"))))))

(deftest settings-panel-clear-on-shrink-toggles-the-live-tui
  (let [cs (assoc (settings-cs)
                  :tui {:clear-on-shrink? (atom false)
                        :show-hardware-cursor? (atom false)})
        saved (atom [])
        focused (atom nil)]
    (with-redefs [core/tui-set-focus (fn [_ c] (reset! focused c))
                  core/tui-request-render (fn [_] nil)
                  cfg/save-setting! (fn [path value] (swap! saved conj [path value]))]
      (ss/show-settings cs)
      (let [frame (:component @(:dock-current cs))
            sl @focused
            row-idx (first (keep-indexed (fn [i item]
                                           (when (= :clear-on-shrink (:id item)) i))
                                         @(:items-atom sl)))]
        (is (some? row-idx) "the row is present with a live tui")
        (reset! (:selected-idx-atom sl) row-idx)
        (is (some #(str/includes? % "Clear on shrink") (plain frame 100))
            "the row renders once selected")
        (core/handle-input sl "\r")
        (is (true? @(:clear-on-shrink? (:tui cs)))
            "the live tui flag flips (pi: setClearOnShrink)")
        (is (= [[[:terminal :clear-on-shrink] true]] @saved)
            "and the setting persists")))))

(deftest settings-panel-theme-row-opens-a-submenu
  (let [cs (assoc (settings-cs)
                  :theme-controller {:config-atom (atom {:theme "dark"})
                                     :terminal-theme-atom (atom :dark)})
        focused (atom nil)]
    (with-redefs [core/tui-set-focus (fn [_ c] (reset! focused c))
                  core/tui-request-render (fn [_] nil)]
      (ss/show-settings cs)
      (let [sl @focused
            row (some #(when (= :theme (:id %)) %) @(:items-atom sl))]
        (is (some? row) "the theme row is present")
        (is (= "dark" (:value row)) "it shows the theme setting")
        (is (fn? (:submenu row)) "it carries a :submenu factory (pi: submenu)")
        (is (nil? (:values row)) "it no longer cycles a flat values list")
        (is (some? ((:submenu row) (:value row) (fn [& _])))
            "the factory builds the theme submenu")))))

(deftest settings-panel-theme-submenu-commits-through-the-row
  (testing "opening the theme row's submenu, picking a theme and Enter
            commits through the settings list's :on-change"
    (let [cs (assoc (settings-cs)
                    :theme-controller {:config-atom (atom {:theme "dark"})
                                       :terminal-theme-atom (atom :dark)})
          focused (atom nil)
          saved (atom [])
          previews (atom [])]
      (with-redefs [core/tui-set-focus (fn [_ c] (reset! focused c))
                    core/tui-request-render (fn [_] nil)
                    ;; other suites register custom themes globally — pin the
                    ;; available set so the row order is deterministic
                    th/get-all-themes (constantly {"dark" :dark "light" :light})
                    theme-ctrl/preview (fn [_ v] (swap! previews conj v))
                    theme-ctrl/set-theme-setting! (fn [_ _] {:success true})
                    cfg/save-setting! (fn [path value] (swap! saved conj [path value]))]
        (ss/show-settings cs)
        (let [frame (:component @(:dock-current cs))
              sl @focused]
          (settings-list/settings-list-select-item! sl :theme)
          (core/handle-input sl "\r")  ;; open the theme submenu
          (is (some #(str/includes? % "Automatic") (plain frame 100))
              "the single-mode menu renders in place of the rows")
          (core/handle-input sl "\u001b[B")  ;; dark → light
          (is (= ["light"] @previews) "the highlight previews the theme")
          (core/handle-input sl "\r")        ;; commit and close
          (is (= [[[:theme] "light"]] @saved) "the theme persists through the row")
          (is (some #(str/includes? % "Theme") (plain frame 100))
              "the main list is back"))))))
