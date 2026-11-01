(ns kmet.ui.tui.test-external-editor
  "The external editor opens expanded text in the session's runtime directory."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [kmet.ui.tui.external-editor :as external-editor]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.core :as tui]
            [kmet.test-tmp :as test-tmp]))

(deftest test-external-editor-spawns-in-the-runtime-cwd
  (testing "the editor process gets the footer provider's cwd as :dir; a cs
            without one falls back to the process cwd"
    (let [project (test-tmp/temp-dir! "test-external-editor-")
          seen (atom [])
          run (fn [cs]
                (with-redefs [tui/tui-suspend! (fn [_] nil)
                              tui/tui-resume! (fn [_] nil)
                              tui/editor-get-expanded-text (fn [_] "text")
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

(deftest test-external-editor-preserves-literal-paste-markers
  (let [ed (editor/make-editor)
        pasted (str/join "\n" (cons "literal [paste #1 +15 lines]" (repeat 14 "payload")))
        edited (str "edited " pasted)
        submitted (atom nil)]
    (tui/handle-input ed (str "\u001b[200~" pasted "\u001b[201~"))
    (let [collapsed (tui/editor-get-text ed)]
      (with-redefs [tui/tui-suspend! (fn [_] nil)
                    tui/tui-resume! (fn [_] nil)
                    proc/process (fn [argv _opts]
                                   (is (= pasted (slurp (last argv))))
                                   (spit (last argv) (str edited "\n"))
                                   (delay {:exit 0}))]
        (external-editor/handle-external-editor {:current-editor-atom (atom ed) :tui nil}))
      (is (= edited (tui/editor-get-expanded-text ed)))
      (tui/editor-set-on-submit! ed #(reset! submitted %))
      (tui/handle-input ed "\r")
      (is (= edited @submitted))
      (tui/handle-input ed (str (char 31)))
      (is (= collapsed (tui/editor-get-text ed)))
      (is (= pasted (tui/editor-get-expanded-text ed)))
      (is (= 1 @(:paste-counter ed)))
      (tui/handle-input ed (str (char 26)))
      (is (= edited (tui/editor-get-expanded-text ed)))
      (is (zero? @(:paste-counter ed)))
      (tui/handle-input ed (str (char 31)))
      (let [next-paste (apply str (repeat 1001 "x"))]
        (tui/handle-input ed (str "\u001b[200~" next-paste "\u001b[201~"))
        (is (= (str pasted next-paste) (tui/editor-get-expanded-text ed)))))))
