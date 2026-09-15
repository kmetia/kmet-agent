(ns kmet.app.tools.util
  "Shared utilities for tool implementations — safe file traversal,
   quiet-title path shortening, etc."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private max-traverse-files 10000)

(defn safe-file-seq
  "Like file-seq but with symlink cycle protection and a max-files limit."
  [dir-path]
  (let [visited (atom #{})]
    (take max-traverse-files
          (filter fs/regular-file?
                  (tree-seq
                   (fn [f]
                     (and (fs/directory? f)
                          (let [cp (fs/canonicalize f)]
                            (when-not (contains? @visited cp)
                              (swap! visited conj cp)
                              true))))
                   (fn [d] (fs/list-dir d))
                   (fs/file dir-path))))))

(defn shorten-title-path
  "Home-relative display path for a quiet title (pi: shortenPath).
   Non-string input passes through — callers guard with string?/seq."
  [p]
  (let [home (System/getProperty "user.home" "")]
    (if (and (string? p) (seq home)
             (or (= p home) (str/starts-with? p (str home "/"))))
      (str "~" (subs p (count home)))
      p)))

(defn title-path-arg
  "First non-blank string among the :path / :file_path arg spellings, or
   nil. Nil-safe over partial streaming args (non-map, missing, blank)."
  [args]
  (when (map? args)
    (let [v (or (:path args) (:file_path args))]
      (when (and (string? v) (seq v)) v))))

(defn title-str-arg
  "Non-blank string value of arg K, or nil. Accepts keyword and string
   keys; nil-safe over partial streaming args."
  [args k]
  (when (map? args)
    (let [v (or (get args k) (get args (name k)))]
      (when (and (string? v) (seq v)) v))))
