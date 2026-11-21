(ns kmet.test-modules-manifest
  "Guard: modules.edn is the workspace manifest (kmet.tasks.modules) and the
   tasks read it directly. bb.edn, deps.edn and modules/*/deps.edn keep
   literal lists because tools.deps readers run before any code exists — this
   guard holds those literals to the manifest, so a module or extension
   added to one place cannot silently miss the others."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.modules :as modules]))

(defn- read-edn [path] (edn/read-string (slurp path)))

(deftest manifest-shape
  (testing "the manifest lists real trees with a rank each"
    (let [ms (modules/modules)]
      (is (<= 4 (count ms)) "the module list is real")
      (is (<= 3 (count (modules/extensions))) "the extension list is real")
      (is (seq (modules/roots)) "the extra root list is real")
      (is (seq (modules/test-root-paths)) "a test root is flagged")
      (doseq [mod ms]
        (is (fs/directory? (:src mod)) (str (:name mod) " src tree"))
        (is (fs/directory? (:test mod)) (str (:name mod) " test tree"))
        (is (int? (:rank mod)) (str (:name mod) " has a rank")))
      (doseq [ext (modules/extensions)]
        (is (fs/directory? (:src ext)) (str (:name ext) " src tree"))
        (is (fs/directory? (:test ext)) (str (:name ext) " test tree"))
        (is (or (nil? (:test-hosts ext)) (= [:bb] (:test-hosts ext)))
            (str (:name ext) " :test-hosts is [:bb] or absent"))))))

(deftest bb-edn-paths-match-the-manifest
  (testing "bb.edn :paths is the manifest classpath list"
    (is (= (modules/classpath-paths) (:paths (read-edn "bb.edn"))))))

(deftest deps-edn-embed-paths-match-the-manifest
  (testing "deps.edn :jolt/build :embed is the manifest embed list"
    (is (= (modules/embed-paths) (get-in (read-edn "deps.edn") [:jolt/build :embed])))))

(deftest deps-edn-includes-runtime-only-host-namespaces
  (testing "deps.edn :jolt/build :include bakes what the script hosts require at runtime"
    (let [includes (set (get-in (read-edn "deps.edn") [:jolt/build :include]))]
      (doseq [ns '[clojure.core.async babashka.cli babashka.tasks]]
        (is (contains? includes ns) (str ns " is baked into a built image"))))))

(deftest dependencies-edn-point-at-manifest-modules
  (testing "every module has a deps.edn whose :paths is the module layout"
    (doseq [mod (modules/modules)]
      (let [dep-edn (read-edn (str (fs/path "modules" (:name mod) "deps.edn")))]
        (is (= ["src" "test"] (:paths dep-edn))
            (str "modules/" (:name mod) "/deps.edn :paths")))))
  (testing "the root deps.edn carries the task root and the extension projects
            (a module local root may not name paths above itself)"
    (is (= (into ["tasks"] (modules/extension-paths))
           (:paths (read-edn "deps.edn")))))
  (testing "the root deps.edn pulls the app module (which pulls the rest)"
    (let [app-mod (some #(when (= "app" (:name %)) %) (modules/modules))
          app-root (str/replace (str (fs/parent (:src app-mod))) "\\" "/")]
      (is (= {:local/root app-root} (get (:deps (read-edn "deps.edn")) 'kmet/app))))))

(deftest artifact-roots-drop-test-tooling-and-extension-trees
  (testing "a resolved classpath ships no test tree, no tasks/ and no
            extension project's src — the staged bundled tree ships instead"
    (is (= ["modules/libs/src" "target/kmet-bundled"]
           (modules/artifact-roots
            ["modules/libs/src" "modules/libs/test"
             "extensions/clojure/src" "extensions/clojure/test"
             "test" "tasks" "target/kmet-bundled"])))
    (testing "absolute roots answer the same"
      (is (= ["/repo/modules/libs/src"]
             (modules/artifact-roots
              ["/repo/modules/libs/src" "/repo/extensions/clojure/src"
               "/repo/test" "/repo/tasks"]))))
    (testing "the classpath order is the jar's order"
      (is (= ["a/src" "b/src"] (modules/artifact-roots ["a/src" "b/src"]))))))

(deftest extension-bb-edn-paths-cover-the-module-trees
  (testing "an extension project's bb.edn lists its own trees and every module source"
    (let [module-srcs (mapv #(str "../../" %) (modules/module-src-paths))]
      (doseq [ext (modules/extensions)]
        (let [dir (str (fs/path (modules/extension-root) (:name ext)))
              bb-edn (str (fs/path dir "bb.edn"))]
          (is (fs/exists? bb-edn) (str bb-edn " exists"))
          (when (fs/exists? bb-edn)
            (let [paths (:paths (read-edn bb-edn))]
              (is (every? #(fs/directory? (fs/path dir %)) paths)
                  (str bb-edn " declares only real directories"))
              (is (every? (set paths) (into ["src" "test"] module-srcs))
                  (str bb-edn " covers its own trees and the module sources")))))))))

(deftest derived-test-registry-is-nonempty
  (testing "the scan covers every test root"
    (let [files (modules/test-namespace-files)]
      (is (<= 150 (count files)) "the scan found the suite")
      (is (= (mapv :ns files) (distinct (mapv :ns files)))
          "no duplicate namespaces")
      (doseq [root (modules/test-roots)]
        (is (some #(str/starts-with? (str/replace (:file %) "\\" "/")
                                     (str (str/replace root "\\" "/") "/"))
                  files)
            (str "no test namespaces found under " root))))))

(deftest ^:bb-only bb-only-test-projects-carry-only-bb-only-vars
  ;; Jolt and the JVM skip these roots without requiring them, so the skip is
  ;; only safe while every test var there is ^:bb-only. This guard runs on bb,
  ;; where the namespaces load, and holds the manifest flag honest.
  (let [names (modules/bb-only-test-namespaces)]
    (is (seq names) "the manifest marks at least one bb-only test project")
    (doseq [ns-sym names]
      (require ns-sym)
      (let [test-vars (filter #(:test (meta %)) (vals (ns-interns ns-sym)))
            non-bb (remove #(:bb-only (meta %)) test-vars)]
        (is (seq test-vars) (str ns-sym " has no test vars"))
        (is (empty? non-bb)
            (str ns-sym " has non-bb-only test vars: "
                 (vec (sort (map (comp str :name meta) non-bb)))))))))
