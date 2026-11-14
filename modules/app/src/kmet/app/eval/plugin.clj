(ns kmet.app.eval.plugin
  "The `:tool/eval` row: mounts the eval tool and `/eval` command together."
  (:require [kmet.app.commands :as commands]
            [kmet.app.eval.command :as eval-command]
            [kmet.app.eval.tool :as eval-tool]
            [kmet.app.tools.registry :as registry]
            [kmet.srce.core :as srce]))

(srce/defplugin plugin
  {:name :tool/eval
   :inject [:tools :commands]
   :apply (fn [ctx config]
            (registry/mount-tool! ctx eval-tool/definition config)
            (commands/mount-command! ctx eval-command/command :tool/eval)
            nil)})
