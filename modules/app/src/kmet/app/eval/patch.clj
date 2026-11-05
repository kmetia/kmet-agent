(ns kmet.app.eval.patch
  "Structural source editing for one eval-library definition.

   The file is parsed with rewrite-clj, the named definition's top-level form
   is selected by its exact recorded source, and the first structurally equal
   subform inside that form is replaced. Sibling definitions and the file's
   `(ns …)` form are never candidates."
  (:require [kmet.libs.clj-edit :as clj-edit]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(defn- bad-patch! [message data]
  (throw (ex-info message (assoc data :type :eval/patch))))

(defn- subform-match? [node form]
  (try (= form (n/sexpr node))
       (catch Throwable _ false)))

(defn- replace-first-subform
  "Replace the first node in NODE's subtree whose value structurally equals
   FORM with REPLACEMENT. Returns `[node replaced?]`."
  [node form replacement]
  (if (subform-match? node form)
    [replacement true]
    (if-let [children (try (seq (n/children node))
                           (catch Throwable _ nil))]
      (loop [children (vec children)
             i 0]
        (if (< i (count children))
          (let [[child replaced?] (replace-first-subform (nth children i)
                                                         form replacement)]
            (if replaced?
              [(n/replace-children node (assoc children i child)) true]
              (recur children (inc i))))
          [node false]))
      [node false])))

(defn patched-content
  "Return CONTENT with the first subform equal to OLD replaced by NEW inside
   RECORD's own top-level form. Throws `:eval/patch` for an unreadable file,
   a missing definition form, an unreadable replacement, or no match."
  [content record old new]
  (let [root (try (p/parse-string-all content)
                  (catch Throwable t
                    (bad-patch! (str "cannot parse the library file: "
                                     (ex-message t))
                                {:file (:file record)})))
        children (vec (n/children root))
        idx (clj-edit/definition-node-index children record)]
    (when (nil? idx)
      (bad-patch! (str "the definition form for " (:name record)
                       " is not in the library file")
                  {:name (:name record)}))
    (let [replacement (try (p/parse-string (pr-str new))
                           (catch Throwable t
                             (bad-patch! (str "cannot parse replacement: "
                                              (ex-message t))
                                         {:name (:name record) :form new})))
          [patched-def replaced?] (replace-first-subform
                                   (nth children idx) old replacement)]
      (when-not replaced?
        (bad-patch! (str "no subform in " (:name record) " matches "
                         (pr-str old))
                    {:name (:name record) :form old}))
      (n/string (n/replace-children root (assoc children idx patched-def))))))

