(ns kmet.app.ui.test-settings-selector
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.ui :as ui]
            [kmet.app.ui.settings-selector :as ss]
            [kmet.app.ui.subs :as subs]
            [kmet.libs.terminal-image :as timg]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.core :as core]
            [kmet.tui.hiccup :as h]))

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
                 :value "false"
                 :values ["true" "false"]}
                {:id :image-width-cells
                 :label "Image width"
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
   :chat-history (ui/make-chat-history)
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
