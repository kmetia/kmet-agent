(ns kmet.extensions.local.core
  "Project-local extension: expose the gate and file-search verbs."
  (:require [kmet.extension :as ext]
            [kmet.srce.core :as srce]
            [kmet.extensions.local.files :as files]
            [kmet.extensions.local.gate :as gate]))

(def ^:private source-description
  "Project gates and file search: local/gate, local/glob, local/grep.")

(defn- eval-source
  [ctx]
  (let [root (str (or (:project-dir ctx) (:cwd ctx) "."))
        cwd (str (or (:cwd ctx) root))]
    {:doc source-description
     :ns {'ext.local {'gate (gate/gate-verb root)
                      'glob (files/glob-verb cwd)
                      'grep (files/grep-verb cwd)}}}))

(defn init
  "Register the local gate and file-search eval source."
  [api]
  (ext/register-eval-source! api :local eval-source
                             {:description source-description})
  nil)

(srce/defplugin plugin
  {:name :ext/local
   :apply (fn [_ctx api]
            (init api))})
