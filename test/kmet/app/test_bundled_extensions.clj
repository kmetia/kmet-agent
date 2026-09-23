(ns kmet.app.test-bundled-extensions
  "Bundled extension discovery and its package layer (extension-bundle.md):
   the committed manifest and the gate over the repo's extensions/ tree,
   descriptor resolution in checkout vs artifact (resource) mode, and the
   enabled-state semantics of the :bundled-extensions settings key."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.bundled-extensions :as bundled]
            [kmet.app.packages :as pkgs]
            [kmet.config :as cfg]
            [kmet.tasks.build :as build]
            [kmet.test-utils :refer [slash]]))

(defn- tmp-dir []
  (str (fs/create-temp-dir {:dir (System/getenv "TMPDIR")})))

(defn- with-empty-scopes
  "Run F with the agent dir and project scope pinned to empty temp dirs, so
   the real user's auto-dir extensions stay out of resolution assertions."
  [f]
  (let [agent (tmp-dir)
        cwd (tmp-dir)]
    (with-redefs [cfg/get-agent-dir (fn [] agent)
                  cfg/project-dir (fn [] (str (fs/path cwd ".kmet")))
                  fs/cwd (fn [] cwd)]
      (f))))

(defn- with-fixture-resources
  "Run F with io/resource answering artifact-mode extension keys from the
   test classpath: extensions/<x> maps to fixtures/<x>."
  [f]
  (let [orig io/resource
        mapped (fn [key]
                 (let [k (str key)]
                   (if (str/starts-with? k "extensions/")
                     (orig (str "fixtures/" (subs k (count "extensions/"))))
                     (orig key))))]
    (with-redefs [io/resource mapped] (f))))

;; ─── Manifest and the bundle gate ─────────────────────────────────────────

(t/deftest test-manifest-is-complete-and-well-formed
  (let [m (bundled/manifest)]
    (t/is (= 1 (:schema m)))
    (t/is (= 10 (count (:artifacts m))))
    (t/is (= #{"clojure" "grep-tool" "find-tool" "ls-tool" "tools" "deepseek-peak"
               "lsp-adapter" "mcp-adapter" "review" "tree-sitter"}
             (set (map :name (:artifacts m)))))
    (t/is (every? #(contains? #{:dir :file} (:kind %)) (:artifacts m)))
    (t/is (every? #(string? (:root %)) (:artifacts m)))))

(t/deftest test-bundle-gate-accepts-the-committed-tree
  ;; bb check-bundled-extensions is this validator: the committed manifest
  ;; covers every artifact under extensions/ and every root passes the D8
  ;; restrictions
  (let [m (build/validate-bundled-extensions!)]
    (t/is (= (set (map :root (:artifacts (bundled/manifest))))
             (set (map :root (:artifacts m)))))
    (t/is (= 10 (count (:artifacts m))))))

(t/deftest test-checkout-artifacts-are-real-repo-paths
  ;; the test run is a checkout: the manifest answers a file: URL, so every
  ;; descriptor is a real path under extensions/ (no resource prefix)
  (let [arts (bundled/artifacts)]
    (t/is (= 10 (count arts)))
    (t/is (every? #(= (:root %) (:path %)) arts))
    (t/is (every? #(fs/exists? (:root %)) arts))
    (t/is (every? :bundled? arts))
    (let [clojure-d (first (filter #(= "clojure" (:name %)) arts))]
      (t/is (= :dir (:kind clojure-d)))
      (t/is (fs/regular-file? (str (fs/path (:root clojure-d) "extension.edn")))))))

;; ─── Descriptor resolution (pure helpers) ─────────────────────────────────

(t/deftest test-checkout-descriptors-skip-missing-and-excluded
  (let [repo (tmp-dir)
        ext (str (fs/path repo "extensions"))]
    (fs/create-dirs (str ext "/good"))
    (spit (str ext "/good/extension.edn") "{:name \"good\" :entry good.core :loader [:sci]}\n")
    (spit (str ext "/single.clj") "(ns single)\n")
    (let [manifest {:artifacts [{:name "good" :kind :dir :root "good"}
                                {:name "single" :kind :file :root "single.clj"}
                                {:name "ghost" :kind :dir :root "ghost"}
                                {:name "excluded" :kind :file :root "single.clj"}]
                    :exclude ["single.clj"]}
          descs (bundled/checkout-descriptors manifest repo)]
      (t/is (= ["good"] (mapv :name descs)))
      (t/is (= [(slash (str (fs/path ext "good")))] (slash (mapv :path descs)))))))

(t/deftest test-resource-mode-descriptors
  (with-fixture-resources
    (fn []
      (let [manifest {:artifacts [{:name "dir" :kind :dir :root "ext-dir"}
                                  {:name "file" :kind :file :root "ext-single/hello_ext.clj"}
                                  {:name "ghost" :kind :dir :root "ghost"}]}
            descs (bundled/resource-descriptors manifest)]
        (t/is (= ["dir" "file"] (mapv :name descs)))
        (t/is (= {:kind :resource-dir :prefix "extensions/ext-dir"}
                 (select-keys (first descs) [:kind :prefix])))
        (t/is (= {:kind :resource-file :path "extensions/ext-single/hello_ext.clj"}
                 (select-keys (second descs) [:kind :path])))
        (t/is (every? :bundled? descs))))))

(t/deftest test-malformed-manifest-resolves-to-nothing
  ;; never throws: a broken bundle must not keep the app from starting
  (with-redefs [bundled/manifest (constantly nil)]
    (t/is (= [] (bundled/artifacts)))))

;; ─── The package-item layer (kmet.app.packages) ───────────────────────────

(t/deftest test-items-enabled-state
  (let [root (tmp-dir)
        _ (spit (str root "/extension.edn") "{:name \"demo\" :entry demo.core :loader [:sci]}\n")
        descriptor {:name "demo" :kind :dir :root root :path root :bundled? true}]
    (with-redefs [bundled/artifacts (fn [] [descriptor])]
      (let [items (pkgs/bundled-items {} {})]
        (t/is (= 1 (count items)))
        (t/is (false? (:enabled (first items))) "default disabled")
        (t/is (= :bundled (get-in (first items) [:metadata :origin])))
        (t/is (= "demo" (get-in (first items) [:metadata :display-name])))
        (t/is (= descriptor (get-in (first items) [:metadata :artifact]))))
      (t/is (true? (:enabled (first (pkgs/bundled-items {:bundled-extensions ["demo"]} {})))))
      (t/is (true? (:enabled (first (pkgs/bundled-items {:bundled-extensions ["+demo"]} {})))))
      (t/is (false? (:enabled (first (pkgs/bundled-items {:bundled-extensions ["demo"]}
                                                         {:bundled-extensions ["-demo"]}))))
            "project disable wins")
      (t/is (true? (:enabled (first (pkgs/bundled-items {}
                                                        {:bundled-extensions ["+demo"]}))))
            "project enable over the disabled default")
      (t/is (true? (:enabled (first (pkgs/bundled-items {:bundled-extensions ["demo"]}
                                                        {:bundled-extensions ["+demo"]}))))
            "a project + re-affirms the global enable")
      (t/is (false? (:enabled (first (pkgs/bundled-items {:bundled-extensions ["demo" "-demo"]}
                                                         {}))))))))

(t/deftest test-1-4-arities-exclude-the-bundled-layer
  (let [descriptor {:name "demo" :kind :file :root "/x/demo.clj"
                    :path "/x/demo.clj" :bundled? true}]
    (with-redefs [bundled/artifacts (fn [] [descriptor])]
      (with-empty-scopes
        (fn []
          (let [items (pkgs/bundled-items {:bundled-extensions ["demo"]} {})]
            (t/is (= 1 (count items)))
            (doseq [res [(pkgs/resolve-package-items {} {})
                         (pkgs/resolve-package-items {} {} nil)
                         (pkgs/resolve-package-items {} {} nil true)]]
              (t/is (empty? (:extensions res))))
            (let [res (pkgs/resolve-package-items {} {} nil true items)]
              (t/is (= [(slash "/x/demo.clj")] (slash (mapv :path (:extensions res)))))
              (t/is (every? :enabled (:extensions res))))))))))
