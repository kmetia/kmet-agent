(ns kmet.app.eval.format
  "Small formatting helpers shared by the eval listings: the interface text
   for a definition or a source-contributed verb."
  (:require [clojure.string :as str]
            [kmet.libs.text :as text]))

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

(defn source-id
  "SOURCE's id as text. `:name` drops a keyword's colon; `:keyword` keeps the
   printed keyword. A string id stays as written."
  ([source] (source-id source :name))
  ([source style]
   (let [id (if (map? source) (:id source) source)]
     (if (and (= style :keyword) (keyword? id))
       (str id)
       (if (keyword? id) (name id) (str id))))))

(defn source-line
  "One source report line: id, doc and indented verb lines, or the source's
   error. STYLE selects how a keyword id prints (`:name` or `:keyword`)."
  ([source] (source-line source :name))
  ([source style]
   (let [id (source-id source style)]
     (if-let [error (:error source)]
       (str "- " id ": " error)
       (str "- " id
            (when-let [doc (:doc source)] (str ": " doc))
            (when (seq (:verbs source))
              (str "\n" (str/join "\n" (map verb-line (:verbs source))))))))))
