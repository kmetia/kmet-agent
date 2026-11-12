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

(defn display-name
  "The call-surface spelling of NAME: core and eval verbs are unqualified,
   an extension drops its internal `ext.` prefix, and a user definition drops
   `user/`. Other namespaces keep their prefix."
  [name]
  (let [s (str name)]
    (cond
      (str/starts-with? s "core/") (subs s 5)
      (str/starts-with? s "eval/") (subs s 5)
      (str/starts-with? s "ext.") (subs s 4)
      (and (str/starts-with? s "user/") (not (str/includes? s " "))) (subs s 5)
      :else s)))

(defn verb-line
  "One indented source-verb line: name, arglists and doc (or `(no docstring)`)."
  [verb]
  (str "    " (display-name (:name verb))
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
