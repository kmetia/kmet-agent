(ns kmet.app.test-keybindings
  (:require [clojure.test :as t]
            [babashka.fs :as fs]
            [kmet.app.keybindings :as kb]
            [kmet.tui.keys :as keys]
            [kmet.tui.keybindings :as tui-kb]))

(defn- temp-agent-dir
  "Create a throwaway agent dir under target/ (with optional keybindings.edn
   content) and return its path."
  ([]
   (temp-agent-dir nil))
  ([content]
   (let [dir (str (fs/absolutize (fs/file "target" (str "test-kb-" (System/currentTimeMillis)))))]
     (fs/create-dirs dir)
     (when content
       (spit (str dir "/keybindings.edn") content))
     dir)))

(defn- cleanup
  [agent-dir]
  (fs/delete-tree agent-dir))

(defn- kb-file
  [agent-dir]
  (str agent-dir "/keybindings.edn"))

(t/deftest test-defaults-when-no-file
  (let [dir (temp-agent-dir)
        kmgr (kb/create-agent-keybindings-manager dir)]
    (try
      (t/is (= ["ctrl+o"] (tui-kb/get-keys kmgr "app.tools.expand")))
      (t/is (= ["ctrl+t"] (tui-kb/get-keys kmgr "app.thinking.toggle")))
      (finally (cleanup dir)))))

(t/deftest test-persisted-overrides
  (let [dir (temp-agent-dir "{\"app.tools.expand\" \"ctrl+e\"\n \"app.thinking.toggle\" [\"ctrl+t\" \"alt+t\"]}")
        kmgr (kb/create-agent-keybindings-manager dir)]
    (try
      (t/is (= ["ctrl+e"] (tui-kb/get-keys kmgr "app.tools.expand")))
      (t/is (= ["ctrl+t" "alt+t"] (tui-kb/get-keys kmgr "app.thinking.toggle")))
      (t/testing "unset ids keep defaults"
        (t/is (= ["ctrl+d"] (tui-kb/get-keys kmgr "app.exit"))))
      (finally (cleanup dir)))))

(t/deftest test-legacy-name-migration
  (t/testing "legacy camelCase ids migrate to namespaced ids (pi KEYBINDING_NAME_MIGRATIONS)"
    (let [dir (temp-agent-dir "{\"expandTools\" \"ctrl+e\"\n \"toggleThinking\" \"ctrl+y\"}")
          kmgr (kb/create-agent-keybindings-manager dir)]
      (try
        (t/is (= ["ctrl+e"] (tui-kb/get-keys kmgr "app.tools.expand")))
        (t/is (= ["ctrl+y"] (tui-kb/get-keys kmgr "app.thinking.toggle")))
        (finally (cleanup dir)))))
  (t/testing "namespaced id wins when both present"
    (let [dir (temp-agent-dir "{\"expandTools\" \"ctrl+e\"\n \"app.tools.expand\" \"ctrl+x\"}")
          kmgr (kb/create-agent-keybindings-manager dir)]
      (try
        (t/is (= ["ctrl+x"] (tui-kb/get-keys kmgr "app.tools.expand")))
        (finally (cleanup dir))))))

(t/deftest test-invalid-values-dropped
  (let [dir (temp-agent-dir "{\"app.clear\" 42\n \"app.exit\" [\"ctrl+d\" 7]\n \"app.tools.expand\" \"ctrl+e\"}")
        kmgr (kb/create-agent-keybindings-manager dir)]
    (try
      (t/is (= ["ctrl+e"] (tui-kb/get-keys kmgr "app.tools.expand")))
      (t/testing "non-string / non-vector-of-strings values fall back to defaults"
        (t/is (= ["ctrl+c"] (tui-kb/get-keys kmgr "app.clear")))
        (t/is (= ["ctrl+d"] (tui-kb/get-keys kmgr "app.exit"))))
      (finally (cleanup dir)))))

(t/deftest test-missing-or-malformed-file
  (t/testing "missing file yields empty bindings"
    (let [dir (temp-agent-dir)]
      (try
        (t/is (= {} (kb/load-user-bindings (kb-file dir))))
        (finally (cleanup dir)))))
  (t/testing "malformed EDN yields empty bindings, not an exception"
    (let [dir (temp-agent-dir "{not valid edn")]
      (try
        (t/is (= {} (binding [*err* (java.io.StringWriter.)]
                      (kb/load-user-bindings (kb-file dir)))))
        (finally (cleanup dir))))))

(t/deftest test-migrate-keybindings-config-file!
  (t/testing "legacy ids rewritten in place (pi: migrateKeybindingsConfigFile)"
    (let [dir (temp-agent-dir "{\"expandTools\" \"ctrl+e\"}")]
      (try
        (kb/migrate-keybindings-config-file! (kb-file dir))
        (t/is (= "{\"app.tools.expand\" \"ctrl+e\"}\n"
                 (slurp (kb-file dir))))
        (t/is (= ["ctrl+e"] (tui-kb/get-keys (kb/create-agent-keybindings-manager dir)
                                             "app.tools.expand")))
        (finally (cleanup dir)))))
  (t/testing "already-namespaced file is left untouched"
    (let [dir (temp-agent-dir "{\"app.tools.expand\" \"ctrl+e\"}")]
      (try
        (kb/migrate-keybindings-config-file! (kb-file dir))
        (t/is (= "{\"app.tools.expand\" \"ctrl+e\"}" (slurp (kb-file dir))))
        (finally (cleanup dir)))))
  (t/testing "missing file is a no-op"
    (let [dir (temp-agent-dir)]
      (try
        (kb/migrate-keybindings-config-file! (kb-file dir))
        (t/is (not (fs/exists? (kb-file dir))))
        (finally (cleanup dir))))))

(t/deftest test-reload-re-reads-file
  (let [dir (temp-agent-dir "{\"app.tools.expand\" \"ctrl+e\"}")
        prev-global (tui-kb/get-global-keybindings)]
    (try
      (tui-kb/set-global-keybindings! (kb/create-agent-keybindings-manager dir))
      (t/is (= ["ctrl+e"] (tui-kb/get-keys (tui-kb/get-global-keybindings) "app.tools.expand")))
      (spit (kb-file dir) "{\"app.tools.expand\" \"ctrl+shift+e\"}")
      (kb/reload-agent-keybindings! dir)
      (t/is (= ["ctrl+shift+e"] (tui-kb/get-keys (tui-kb/get-global-keybindings) "app.tools.expand")))
      (finally
        (cleanup dir)
        (tui-kb/set-global-keybindings! prev-global)))))

(t/deftest test-key-labels-are-shared-and-manager-backed
  ;; §7.1: one label table for how a chord reads (pageUp → pgup, arrows
  ;; → glyphs). keys/key-label is the pure single-chord form; the manager
  ;; level joins labels across an id's chords like key-text does.
  (t/testing "pure label mapping"
    (t/is (= "pgup" (keys/key-label "pageUp")))
    (t/is (= "pgdn" (keys/key-label "pageDown")))
    (t/is (= "esc" (keys/key-label "escape")))
    (t/is (= "↑" (keys/key-label "up")))
    (t/is (= "↓" (keys/key-label "down")))
    (t/is (= "←" (keys/key-label "left")))
    (t/is (= "→" (keys/key-label "right")))
    (t/testing "chords without a label render as themselves"
      (t/is (= "ctrl+e" (keys/key-label "ctrl+e")))
      (t/is (= "enter" (keys/key-label "enter")))
      (t/is (= "a" (keys/key-label "a"))))
    (t/testing "a modified key relabels its key part only"
      (t/is (= "alt+backspace" (keys/key-label "alt+backspace")))
      (t/is (= "shift+←" (keys/key-label "shift+left")))
      (t/is (= "ctrl+↑" (keys/key-label "ctrl+up")))))
  (t/testing "manager level: every chord of an id, joined with /"
    (let [dir (temp-agent-dir)
          kmgr (kb/create-agent-keybindings-manager dir)]
      (try
        (t/is (= ["up"] (tui-kb/get-keys kmgr "tui.editor.cursorUp")))
        (t/is (= "↑" (tui-kb/key-label-text kmgr "tui.editor.cursorUp")))
        (t/is (= "pageUp/ctrl+pageUp" (tui-kb/key-text kmgr "tui.editor.pageUp"))
              "key-text stays the raw machine form")
        (t/is (= "pgup/ctrl+pgup" (tui-kb/key-label-text kmgr "tui.editor.pageUp"))
              "…while key-label-text is the display form")
        (t/testing "multi-chord ids join their labels"
          (t/is (= "alt+left/ctrl+left/alt+b" (tui-kb/key-text kmgr "tui.editor.cursorWordLeft")))
          (t/is (= "alt+←/ctrl+←/alt+b" (tui-kb/key-label-text kmgr "tui.editor.cursorWordLeft"))
                "only the arrow part is relabelled"))
        (t/is (nil? (tui-kb/key-label-text kmgr "app.not.a.binding")))
        (finally (cleanup dir)))))
  (t/testing "user overrides flow through the label form"
    (let [dir (temp-agent-dir "{\"app.exit\" [\"pageUp\" \"q\"]}")
          kmgr (kb/create-agent-keybindings-manager dir)]
      (try
        (t/is (= "pgup/q" (tui-kb/key-label-text kmgr "app.exit")))
        (finally (cleanup dir))))))

(t/deftest test-kmet-deltas-and-extension-ids-on-pi-tables
  ;; The TUI table mirrors pi's TUI_KEYBINDINGS plus a fixed set of kmet
  ;; deltas (chords folded onto pi ids) and kmet-only ids. Pinned here so a
  ;; rename or drop fails loudly at the manager level; tui.md §7 lists
  ;; the same set.
  (let [kmgr (kb/make-agent-keybindings-manager)]
    (t/testing "folded kmet chords on pi ids"
      (t/is (= ["backspace" "ctrl+h"] (tui-kb/get-keys kmgr "tui.editor.deleteCharBackward")))
      (t/is (= ["tab" "ctrl+i"] (tui-kb/get-keys kmgr "tui.input.tab")))
      (t/is (= ["up" "ctrl+p"] (tui-kb/get-keys kmgr "tui.select.up")))
      (t/is (= ["down" "ctrl+n"] (tui-kb/get-keys kmgr "tui.select.down")))
      (t/is (= ["shift+enter" "ctrl+j" "ctrl+enter" "alt+enter"]
               (tui-kb/get-keys kmgr "tui.input.newLine")))
      (t/is (= ["ctrl+alt+]" "ctrl+shift+]"] (tui-kb/get-keys kmgr "tui.editor.jumpBackward")))
      (t/is (= ["escape" "ctrl+c"] (tui-kb/get-keys kmgr "tui.select.cancel")))
      (t/is (= ["shift+l" "L"] (tui-kb/get-keys kmgr "app.tree.editLabel"))
            "legacy bare-L encoding rides the id"))
    (t/testing "kmet-only ids"
      (t/is (= ["ctrl+z"] (tui-kb/get-keys kmgr "tui.editor.redo")))
      (t/is (= ["ctrl+w"] (tui-kb/get-keys kmgr "tui.editor.killLine")))
      (t/is (= ["home"] (tui-kb/get-keys kmgr "tui.select.first")))
      (t/is (= ["end"] (tui-kb/get-keys kmgr "tui.select.last")))
      (t/is (= ["left"] (tui-kb/get-keys kmgr "tui.settings.cycleBackward")))
      (t/is (= ["right"] (tui-kb/get-keys kmgr "tui.settings.cycleForward")))
      (t/is (= ["ctrl+s"] (tui-kb/get-keys kmgr "app.thinking.save"))
            "pi id kmet was missing")
      (t/is (= ["ctrl+q"] (tui-kb/get-keys kmgr "app.quit"))
            "global quit (kmet-only id)"))
    (t/testing "user overrides replace the whole chord set"
      (tui-kb/set-user-bindings! kmgr {"tui.editor.deleteCharBackward" "ctrl+x"})
      (t/is (= ["ctrl+x"] (tui-kb/get-keys kmgr "tui.editor.deleteCharBackward")))
      (tui-kb/set-user-bindings! kmgr {}))))


