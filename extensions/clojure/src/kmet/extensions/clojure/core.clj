;; clojure extension — Clojure-aware tools for kmet.
;;
;; Registers clojure_edit directly (the family's entry point) and the other
;; three as one `:deferred` tool source — their schemas are not declared until
;; `tool_search` loads them:
;;   clojure_edit              — structure-aware form editing (direct)
;;   clojure_edit_replace_sexp — s-expression replacement (deferred)
;;   clojure_paren_repair      — delimiter repair (deferred)
;;   clojure_eval              — nREPL evaluation (deferred)

(ns kmet.extensions.clojure.core
  (:require [clojure.java.io :as io]
            [kmet.extension :as ext]
            [kmet.srce.core :as srce]
            [kmet.extensions.clojure.edit-tool :as edit-tool]
            [kmet.extensions.clojure.eval-tool :as eval-tool]
            [kmet.libs.nrepl :as nrepl]
            [kmet.extensions.clojure.paren-repair :as paren-repair]
            [kmet.extensions.clojure.sexp-tool :as sexp-tool]))

(defn init
  "Register the family's entry point directly and its refinements as one
   `:deferred` source: `clojure_edit` is what every structural edit starts
   with, while the other three are situational, so their schemas stay out of
   the declared set until `tool_search` loads them (docs/architecture.md
   § Tools and the script sandbox)."
  [api]
  (ext/register-tool! api (edit-tool/definition))
  (ext/register-tool-source!
   api ::editing
   (fn []
     {"clojure_edit_replace_sexp" (sexp-tool/definition)
      "clojure_paren_repair" (paren-repair/definition)
      "clojure_eval" (eval-tool/definition)})
   {:exposure :deferred
    :description
    (str "Clojure editing refinements — clojure_edit_replace_sexp (replace one "
         "expression inside a form), clojure_paren_repair (fix unbalanced "
         "delimiters after a broken edit), clojure_eval (evaluate in a running "
         "nREPL)")})
  ;; the paren-check hooks: write (pre, reject) + edit (post, warn)
  (ext/on-tool api paren-repair/on-tool)
  ;; contribute the editing-guidelines skill (self-registered content —
  ;; no host path enumeration, so jar/zip artifacts work unexpanded)
  (ext/register-skill! api (slurp (io/resource "skills/clojure-edit/SKILL.md"))
                       {:location "clojure:skills/clojure-edit/SKILL.md"}))

(defn shutdown [_api]
  ;; best-effort: close the nREPL sessions this process created
  (nrepl/close-sessions!))

;; the plugin the loader mounts; its body is `init` above
(srce/defplugin plugin
  {:name :ext/clojure
   :apply (fn [_ctx api] (init api) nil)})
