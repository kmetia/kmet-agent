(ns kmet.app.eval.format
  "Small formatting helpers shared by the eval listings: the interface text
   for a definition or a source-contributed verb."
  (:require [kmet.libs.text :as text]))

(defn arglists-text
  "The printed arglists with a leading space, or nil when there are none."
  [arglists]
  (when (seq arglists)
    (str " " (pr-str arglists))))

(defn doc-line
  "The first non-blank line of DOC, or nil."
  [doc]
  (some-> doc text/first-nonblank-line))

(defn verb-line
  "One indented source-verb line: name, arglists and doc (or `(no docstring)`)."
  [verb]
  (str "    " (:name verb)
       (arglists-text (:arglists verb))
       " — "
       (or (doc-line (:doc verb)) "(no docstring)")))
