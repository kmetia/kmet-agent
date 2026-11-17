(ns kmet.test-test-ns-registration
  "Guard (global organization): the test registry is derived, not maintained.
   The runner's all-namespaces comes from kmet.tasks.modules/test-registry
   (a manifest-rooted, content-scanned derivation); this guard re-derives the
   set with an independent walk and checks the two agree, so a broken glob or
   a renamed tree fails loudly instead of silently shrinking the gate."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [clojure.set :as set]
            [kmet.tasks.modules :as modules]
            [kmet.tasks.runner :as runner]))

(defn- test-roots
  "Every test root, from the manifest: the repo-root test/ tree plus each
   module's and extension's own test/ tree."
  []
  (modules/test-roots))

(defn- ns-symbol [root path]
  (-> (str path)
      (str/replace "\\" "/")
      (str/replace (re-pattern (str "^" (java.util.regex.Pattern/quote (str/replace (str root) "\\" "/")) "/")) "")
      (str/replace #"\.clj[ac]?$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")))

(defn- test-bearing-namespaces []
  (->> (test-roots)
       (mapcat (fn [root]
                 (->> (file-seq (java.io.File. root))
                      (filter #(re-find #"\.clj[ac]?$" (str %)))
                      (filter #(str/includes? (slurp %) "deftest"))
                      (map #(ns-symbol root %)))))
       (map symbol)
       set))

(deftest test-roots-cover-module-and-extension-trees
  (testing "every modules/*/test and extensions/*/test tree is scanned"
    (let [roots (set (map #(str/replace (str %) "\\" "/") (test-roots)))]
      (is (seq roots) "no test roots — the manifest scan is vacuous")
      (doseq [dir (->> (concat (fs/list-dir "modules") (fs/list-dir "extensions"))
                       (map #(fs/path % "test"))
                       (filter fs/directory?)
                       (map #(str/replace (str %) "\\" "/")))]
        (is (contains? roots dir)
            (str "test root not scanned: " dir))))))

(deftest test-registry-is-the-derived-scan
  (testing "runner/all-namespaces and an independent re-derivation agree"
    (let [derived (set runner/all-namespaces)
          found (test-bearing-namespaces)]
      (is (seq found)
          "no test-bearing namespaces found — the scan is vacuous")
      (is (<= 150 (count found))
          (str "the scan looks short: " (count found) " namespaces"))
      (is (empty? (set/difference found derived))
          (str "test namespaces missing from the runner registry: "
               (vec (sort (set/difference found derived)))))
      (is (empty? (set/difference derived found))
          (str "runner registry entries with no test-bearing source: "
               (vec (sort (set/difference derived found))))))))
