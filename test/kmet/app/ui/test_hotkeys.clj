(ns kmet.app.ui.test-hotkeys
  "The /hotkeys view: rows resolve through the live keybindings manager
   (keybindings.edn overrides apply, unbound rows drop, extension shortcuts
   get their own section), and the rendered tree carries the title, the
   section headers and both columns."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.app.keybindings :as app-kb]
            [kmet.app.ui.hotkeys :as hotkeys]
            [kmet.tui.keybindings :as tui-kb]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.utils :as u]))

(defn- render-lines
  "Render a fresh /hotkeys view at WIDTH with ANSI stripped; WIRED? is the
   wiring predicate (defaults to everything wired)."
  ([width] (render-lines width (constantly true)))
  ([width wired?]
   (let [c (hotkeys/make-hotkeys-view wired?)]
     (try
       (mapv u/strip-ansi-codes (protocols/render c width))
       (finally (protocols/dispose c))))))

(defn- line-with
  [lines s]
  (first (filter #(str/includes? % s) lines)))

(deftest renders-live-sections
  (testing "sections, resolved labels and the two columns render"
    (let [prev (tui-kb/get-global-keybindings)]
      (try
        (tui-kb/set-global-keybindings! (app-kb/make-agent-keybindings-manager))
        (let [lines (render-lines 84)]
          (is (line-with lines "Keyboard Shortcuts"))
          (is (some #(= "Navigation" (str/trim %)) lines))
          (is (some #(= "Editing" (str/trim %)) lines))
          (is (some #(= "Other" (str/trim %)) lines))
          (is (line-with lines "↑ / ↓ / ←/ctrl+b / →/ctrl+f")
              "chords use the shared key-label form (tui.md §7.1)")
          (is (some #(and (str/includes? % "↑ / ↓")
                          (str/includes? % "Move cursor / browse history"))
                    lines)
              "key and action share a row")
          (is (some #(and (str/includes? % "ctrl+q")
                          (str/includes? % "Quit kmet (from anywhere)"))
                    lines)
              "a kmet-only global binding has a row")
          (is (some #(and (str/includes? % "!!")
                          (str/includes? % "Run bash command"))
                    lines)
              "literal key text (`!!`) renders")
          (is (line-with lines "Slash commands"))
          (is (not (line-with lines "Paste image or text from clipboard"))
              "an unbound id leaves no row")
          (is (not (line-with lines "Extensions"))
              "no extension shortcuts, no Extensions section"))
        (finally (tui-kb/set-global-keybindings! prev)))))
  (testing "a keybindings.edn override moves the displayed chord"
    (let [prev (tui-kb/get-global-keybindings)]
      (try
        (tui-kb/set-global-keybindings!
         (app-kb/make-agent-keybindings-manager {"app.tools.expand" "ctrl+e"}))
        (let [lines (render-lines 84)]
          (is (line-with lines "ctrl+e")
              "the override resolves")
          (is (not (line-with lines "ctrl+o"))
              "the overridden default is gone"))
        (finally (tui-kb/set-global-keybindings! prev))))))

(deftest shows-only-wired-bindings
  (testing "a bound but unwired app action drops its row"
    (let [prev (tui-kb/get-global-keybindings)]
      (try
        (tui-kb/set-global-keybindings! (app-kb/make-agent-keybindings-manager))
        (let [lines (render-lines 84 #(not= % "app.suspend"))]
          (is (not (line-with lines "Suspend to background")))
          (is (line-with lines "Quit kmet (from anywhere)")
              "other app rows stay"))
        (finally (tui-kb/set-global-keybindings! prev)))))
  (testing "a partially wired row keeps only the wired ids"
    (let [prev (tui-kb/get-global-keybindings)]
      (try
        (tui-kb/set-global-keybindings! (app-kb/make-agent-keybindings-manager))
        (let [lines (render-lines 84 #(not= % "app.model.cycleBackward"))]
          (is (some #(and (str/includes? % "ctrl+p")
                          (not (str/includes? % "shift+ctrl+p"))
                          (str/includes? % "Cycle models"))
                    lines)))
        (finally (tui-kb/set-global-keybindings! prev))))))

(deftest mounted-view-follows-the-manager
  (testing "a shortcut registered after the view is mounted shows up without
            re-invoking the command (the body tracks the manager atoms)"
    (let [prev (tui-kb/get-global-keybindings)]
      (try
        (let [kmgr (app-kb/make-agent-keybindings-manager)
              c (do (tui-kb/set-global-keybindings! kmgr)
                    (hotkeys/make-hotkeys-view (constantly true)))]
          (try
            (let [lines (fn [] (mapv u/strip-ansi-codes (protocols/render c 84)))]
              (is (not (line-with (lines) "Extensions")))
              (tui-kb/register-definition! kmgr "ctrl+alt+y"
                                           {:default-keys ["ctrl+alt+y"]
                                            :description "Do the other thing"})
              (is (line-with (lines) "Extensions"))
              (is (some #(and (str/includes? % "ctrl+alt+y")
                              (str/includes? % "Do the other thing"))
                        (lines))))
            (finally (protocols/dispose c))))
        (finally (tui-kb/set-global-keybindings! prev))))))

(deftest renders-extension-shortcuts
  (testing "runtime-registered shortcuts get their own section"
    (let [prev (tui-kb/get-global-keybindings)]
      (try
        (let [kmgr (app-kb/make-agent-keybindings-manager)]
          (tui-kb/set-global-keybindings! kmgr)
          (tui-kb/register-definition! kmgr "ctrl+alt+x"
                                       {:default-keys ["ctrl+alt+x"]
                                        :description "Do the thing"}))
        (let [lines (render-lines 84)]
          (is (some #(= "Extensions" (str/trim %)) lines))
          (is (some #(and (str/includes? % "ctrl+alt+x")
                          (str/includes? % "Do the thing"))
                    lines)))
        (finally (tui-kb/set-global-keybindings! prev))))))
