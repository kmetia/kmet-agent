(ns kmet.sklop.test-self-contained
  "Guard: sklop stays an assembly layer, not an application. Its namespaces
   may require kmet.sklop.*, the public kernel surface (kmet.srce.core) and
   rank-0 helpers (kmet.libs.*); anything else would make the module depend on
   a layer it is supposed to sit above."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.tasks.source-reader :as sr]))

(defn- sklop-files []
  (->> (fs/glob "modules/sklop/src" "**/*.{clj,cljc}")
       (map str)
       sort))

(defn- foreign-requires [path]
  (when-let [form (sr/read-ns-form path "sklop self-containment guard")]
    (->> (for [clause (rest form)
               :when (and (seq? clause) (= :require (first clause)))
               spec (rest clause)]
           (cond
             (symbol? spec) spec
             (vector? spec) (first spec)
             (seq? spec) (first spec)))
         (map str)
         (filter #(str/starts-with? % "kmet."))
         (remove #(or (str/starts-with? % "kmet.sklop.")
                      (= % "kmet.srce.core")
                      (str/starts-with? % "kmet.libs."))))))

(deftest sklop-requires-only-the-kernel-and-rank-0-helpers
  (is (seq (sklop-files)) "the guard found the module sources")
  (doseq [f (sklop-files)]
    (is (empty? (foreign-requires f))
        (str f " requires a namespace above the kernel: "
             (vec (foreign-requires f))))))
