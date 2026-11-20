(ns kmet.extensions.local.core
  "Project-local extension: expose the gate workflow."
  (:require [kmet.extension :as ext]
            [kmet.srce.core :as srce]
            [kmet.extensions.local.gate :as gate]))

(def ^:private source-description
  "Project test gates: local/gate.")

(defn- eval-source
  [ctx]
  (let [cwd (str (or (:cwd ctx) "."))]
    {:doc source-description
     :ns {'ext.local {'gate (gate/gate-verb cwd)}}}))

(defn init
  "Register the local gate eval source."
  [api]
  (ext/register-eval-source! api :local eval-source
                             {:description source-description})
  nil)

(srce/defplugin plugin
  {:name :ext/local
   :apply (fn [_ctx api]
            (init api))})
