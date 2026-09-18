(ns kmet.app.ui.test-external-editor
  "The external editor ($EDITOR on the editor content) spawns in the
   session's runtime working directory, so a session resumed or imported from
   another project opens the editor there rather than in the launch dir."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [kmet.app.ui.external-editor :as external-editor]
            [kmet.app.ui.footer-data-provider :as fdp]
            [kmet.tui.core :as tui]))

(deftest test-external-editor-spawns-in-the-runtime-cwd
  (testing "the editor process gets the footer provider's cwd as :dir; a cs
            without one falls back to the process cwd"
    (let [project (str (fs/absolutize (str "target/test-external-editor-"
                                           (System/currentTimeMillis))))
          seen (atom [])
          run (fn [cs]
                (with-redefs [tui/tui-suspend! (fn [_] nil)
                              tui/tui-resume! (fn [_] nil)
                              external-editor/editor-text-get-expanded (fn [_] "text")
                              proc/process (fn [_argv opts]
                                             (swap! seen conj opts)
                                             (delay {:exit 0}))]
                  (external-editor/handle-external-editor cs)))]
      (try
        (fs/create-dirs project)
        (run {:current-editor-atom (atom nil)
              :footer-provider (fdp/make-footer-data-provider
                                :cwd-atom (atom project))
              :tui nil})
        (is (= project (:dir (last @seen)))
            "the runtime cwd is passed to the editor process")
        (run {:current-editor-atom (atom nil) :footer-provider nil :tui nil})
        (is (= (str (fs/cwd)) (:dir (last @seen)))
            "no provider: the process cwd, as before")
        (finally (fs/delete-tree project))))))
