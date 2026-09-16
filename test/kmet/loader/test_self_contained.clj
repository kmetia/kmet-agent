(ns kmet.loader.test-self-contained
  "Guard: the loader library must stay extractable. Its namespaces may
   require other kmet.loader.* namespaces and third-party deps, but nothing
   else under kmet.* — that is what lets src/kmet/loader/ leave the repo as
   a standalone library (with its own deps.edn) one day. The reverse
   direction is covered by kmet.libs.test-self-contained, which forbids a
   kmet.libs.* namespace from reaching into kmet.loader.* too."
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn- loader-files []
  (->> (fs/list-dir "src/kmet/loader")
       (filter #(re-find #"\.clj[ca]?$" (str %)))
       (map str)))

(defn- ns-form [path]
  ;; Read the ns form itself — a regex over the file stops at the first `)`
  ;; inside the ns docstring and silently checks nothing.
  (let [form (try (read-string (slurp path))
                  (catch Exception e
                    (throw (ex-info (str "self-containment guard cannot read " path) {} e))))]
    (when (and (seq? form) (= 'ns (first form))) form)))

(defn- foreign-kmet-requires [path]
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
         (remove #(str/starts-with? % "kmet.loader.")))))

(deftest loader-is-extractable
  (doseq [f (loader-files)]
    (let [deps (foreign-kmet-requires f)]
      (is (empty? deps)
          (str f " must not require kmet.* namespaces besides kmet.loader.*, found: "
               (vec deps))))))
