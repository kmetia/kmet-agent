;; clojure extension — Clojure-aware tools for kmet.
;;
;; Registers:
;;   clojure_edit              — structure-aware form editing
;;   clojure_edit_replace_sexp — s-expression replacement
;;   clojure_paren_repair      — delimiter repair

(ns kmet.extensions.clojure.core
  (:require [clojure.java.io :as io]
            [kmet.extensions.clojure.edit-tool :as edit-tool]
            [kmet.extensions.clojure.paren-repair :as paren-repair]
            [kmet.extensions.clojure.sexp-tool :as sexp-tool]
            [kmet.extension :as ext]))

(defn init [api]
  (edit-tool/register! api)
  (sexp-tool/register! api)
  (paren-repair/register! api)
  ;; contribute the editing-guidelines skill (self-registered content —
  ;; no host path enumeration, so jar/zip artifacts work unexpanded)
  (ext/register-skill! api (slurp (io/resource "skills/clojure-edit/SKILL.md"))
                       {:location "clojure:skills/clojure-edit/SKILL.md"}))

(defn shutdown [_api]
  nil)
