(ns kmet.libs.test-self-contained
  "Guard: every kmet.libs.* namespace must be self-contained — no requires
   outside kmet.libs.* (app, tui, modes, ai are forbidden; sibling libs
   like kmet.libs.yaml are allowed). The libs tree should be
   extractable as a third-party library package on its own."
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn- lib-files []
  (->> (fs/list-dir "src/kmet/libs")
       (mapcat (fn [f] (if (fs/directory? f) (fs/list-dir f) [f])))
       (filter #(re-find #"\.clj[ca]?$" (str %)))
       (map str)))

(defn- kmet-requires [path]
  (let [content (slurp path)
        ;; DOTALL — the ns docstring spans lines, so .* must cross \n
        ns-block (re-find #"(?s)\(ns\s+[\w.-]+(?:\s+.*?)?\)" content)]
    (when ns-block
      (->> (re-seq #"\[([\w.-]+)(?:\s+:as\s+\w+)?\]" ns-block)
           (map second)
           (filter #(str/starts-with? % "kmet."))
           ;; sibling-lib requires are allowed; everything else is not
           (remove #(str/starts-with? % "kmet.libs."))))))

(deftest libs-are-self-contained
  (doseq [f (lib-files)]
    (let [deps (kmet-requires f)]
      (is (empty? deps)
          (str f " must not require kmet.* namespaces, found: " deps)))))
