(ns kmet.app.tools.core
  "Tool system public API — Tool record, registry, execution and the shared
   invocation pipeline. Re-exports from kmet.app.tools.tool,
   kmet.app.tools.registry and kmet.app.tools.invoke."
  (:require [kmet.app.tools.tool :as tool]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.invoke :as invoke]))

;; ─── From tool.clj (Tool record + helpers) ──────────────────────────────────

(def map->Tool tool/map->Tool)
(def param tool/param)
(def ->json-schema tool/->json-schema)
(def make-tool tool/make-tool)

(def normalize-tool-definition tool/normalize-tool-definition)

;; ─── From registry.clj (registry + execution) ───────────────────────────────

(def get-all-tools registry/get-all-tools)
(def execute-tool registry/execute-tool)
(def register-tool! registry/register-tool!)
(def unregister-tool! registry/unregister-tool!)
(def get-tool registry/get-tool)
(def register-tool-source! registry/register-tool-source!)
(def unregister-tool-source! registry/unregister-tool-source!)
(def get-contributed-tools registry/get-contributed-tools)
(def select-tools registry/select-tools)

;; ─── From invoke.clj (the shared invocation pipeline) ──────────────────────

(def prepare-tool-call invoke/prepare-tool-call)
(def execute-tool-call invoke/execute-tool-call)
(def finish-tool-call invoke/finish-tool-call)
