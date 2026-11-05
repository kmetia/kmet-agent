(ns kmet.app.eval.sources
  "The `:core` eval source: the file verbs every eval environment gets.

   A source is data plus values, read per call. `core-source` receives the
   call's `:verbs` map and puts the two shared file operations under `user`,
   so a call can use `(edit path old new)` / `(write-file path content)` with
   the same implementation the edit and write tools use. The namespace is
   reserved for this source: an extension source may not contribute to it."
  (:require [clojure.string :as str]))

(def core-source-id
  "The one source allowed to contribute to the `user` namespace."
  :core)

(def reserved-source-namespaces
  "Namespaces an extension source may not contribute to: the core source's
   `user` and `clj`, the eval namespace itself, and the fixed namespaces the
   sandbox installs around it."
  #{"user" "clj" "eval" "clojure" "clojure.core"
    "sandbox" "tools" "babashka.process" "kmet.libs.json"})

(def reserved-source-keys
  "Individual built-in keys outside the reserved namespaces. A source that
   contributes one is refused as a whole."
  #{"eval/library" "eval/forget" "eval/sources" "eval/help"
    "user/edit" "user/write-file"
    "clj/edit-form" "clj/replace-sexp" "clj/paren-repair"})

(defn core-source
  "The `:core` source's contribution: the two file verbs under `user`."
  [{:keys [verbs]}]
  {:doc "Core file verbs: (edit path old new) and (write-file path content)."
   :ns {'user {'edit (:edit verbs)
               'write-file (:write-file verbs)}}})

(defn source-key
  "The address of one contributed value — `user/edit`, `license/add!`."
  [namespace name]
  (str namespace "/" name))

(defn reserved-namespace?
  "Is NAMESPACE reserved for a built-in source?"
  [namespace]
  (contains? reserved-source-namespaces (str namespace)))

(defn reserved-key?
  "Is KEY a built-in key an extension source may not contribute?"
  [key]
  (contains? reserved-source-keys key))

(defn core-source?
  "Is SOURCE-ID the core source?"
  [source-id]
  (= core-source-id source-id))

(defn source-doc
  "A source's one-line description, when it is a string."
  [doc]
  (when (and (string? doc) (not (str/blank? doc)))
    (str/trim doc)))
