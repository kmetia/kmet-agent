(ns multi-ext.main
  "Sample multi-file (manifest) extension for tests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kmet.extension :as ext]
            [multi-ext.helper :as helper]))

(defn init [api]
  (ext/register-tool! api {:name (helper/helper-tool-name)
                           :description "manifest tool"
                           :execute (fn [_] {:content "multi-ok"})})
  ;; a callback the app calls long after the load (tui/autocomplete.clj):
  ;; it must still see the extension's own artifact root, so the completion
  ;; comes from the bundled file, not from [].
  (ext/register-command! api
                         {:name "multi-ext-cmd"
                          :description "manifest command"
                          :get-argument-completions
                          (fn [_prefix]
                            (if-let [u (io/resource "ext-fixture-data.edn")]
                              [{:value (str/trim (slurp u))}]
                              []))})
  (ext/on-event api :agent-end (fn [_ev] nil)))

(defn shutdown [api]
  nil)
