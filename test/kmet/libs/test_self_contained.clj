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

(defn- ns-form [path]
  ;; Read the ns form itself. A regex over the file was the original
  ;; implementation and it silently checked almost nothing: `.*?` stops at
  ;; the first `)` inside the ns docstring, long before `:require`.
  (let [form (try (read-string (slurp path))
                  (catch Exception e
                    (throw (ex-info (str "self-containment guard cannot read " path) {} e))))]
    (when (and (seq? form) (= 'ns (first form))) form)))

(defn- kmet-requires [path]
  (when-let [form (ns-form path)]
    (->> (for [clause (rest form)
               :when (and (seq? clause) (= :require (first clause)))
               spec (rest clause)]
           (cond
             (symbol? spec) spec
             (vector? spec) (first spec)
             (seq? spec) (first spec)))
         (map str)
         (filter #(str/starts-with? % "kmet."))
         ;; sibling-lib requires are allowed; everything else is not
         (remove #(str/starts-with? % "kmet.libs.")))))

(deftest libs-are-self-contained
  (doseq [f (lib-files)]
    (let [deps (kmet-requires f)]
      (is (empty? deps)
          (str f " must not require kmet.* namespaces, found: " (vec deps))))))
