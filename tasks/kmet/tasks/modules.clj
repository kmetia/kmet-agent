(ns kmet.tasks.modules
  "The workspace manifest (modules.edn at the repo root): the single source
   of truth for the module and extension trees and the extra source roots.
   Tasks read it instead of repeating the paths, and the test registry is
   derived from the tree (`test-namespaces`) instead of a hand list.

   bb.edn, deps.edn and modules/*/deps.edn keep literal lists: tools.deps
   readers run before any code exists. kmet.test-modules-manifest holds those
   literals to this manifest."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn manifest
  "The parsed modules.edn. Read per call — the tasks are short-lived, and an
   edit is visible without a process restart."
  []
  (edn/read-string (slurp "modules.edn")))

(defn modules
  "The module list, in classpath/embed order."
  []
  (:modules (manifest)))

(defn extension-root
  "The tree that holds the extension projects."
  []
  (:extension-root (manifest)))

(defn extensions
  "The extension projects (each with its own deps.edn)."
  []
  (:extensions (manifest)))

(defn roots
  "The classpath source roots beyond the module and extension trees."
  []
  (mapv :path (:roots (manifest))))

(defn test-root-paths
  "The non-module test root(s) — the ones flagged :test in :roots."
  []
  (into [] (keep #(when (:test %) (:path %)) (:roots (manifest)))))

(defn module-src-paths [] (mapv :src (modules)))
(defn module-test-paths [] (mapv :test (modules)))

(defn module-paths
  "Each module's src and test root, in manifest order."
  []
  (vec (mapcat (juxt :src :test) (modules))))

(defn extension-src-paths [] (mapv :src (extensions)))
(defn extension-test-paths [] (mapv :test (extensions)))

(defn extension-paths
  "Each extension project's src and test root, in manifest order."
  []
  (vec (mapcat (juxt :src :test) (extensions))))

(defn bb-only-test-roots
  "Test roots whose manifest entry is restricted to babashka (`:test-hosts
   [:bb]`). Every test var under one is ^:bb-only, so the runner skips the
   tree on Jolt and the JVM instead of requiring namespaces that select no
   vars."
  []
  (into []
        (keep (fn [entry]
                (when (and (:test entry) (= [:bb] (:test-hosts entry)))
                  (:test entry))))
        (concat (modules) (extensions))))

(defn classpath-paths
  "The bb.edn :paths list: module trees, extra roots, extension projects."
  []
  (into (module-paths) (concat (roots) (extension-paths))))

(def ^:private non-artifact-root-names
  "Directory names a built artifact never ships: every test tree in the
   manifest is named `test`, and `tasks/` is the build tooling root."
  #{"test" "tasks"})

(defn- extension-src-root?
  "An extension project's src tree is not an app source root: a packaged
   artifact carries the staged bundled tree (extensions/<name>/src/…, the
   resource keys the loader reads) instead of a kmet.extensions.* classpath
   copy nobody requires."
  [root]
  (let [normalized (-> (str root)
                       (str/replace "\\" "/")
                       (str/replace #"/+$" ""))]
    (boolean (some (fn [p] (or (= normalized p)
                               (str/ends-with? normalized (str "/" p))))
                   (extension-src-paths)))))

(defn artifact-roots
  "The classpath ROOTS a built artifact ships, in order: the list minus every
   test tree, the tooling root, and the extension projects' src trees. A
   resolved classpath carries every module's `test` dir because the module
   deps.edn declares `src` and `test` (the runner shares the project's), and
   the root deps.edn carries the extension projects a checkout run resolves
   from — neither belongs in a release artifact, which ships the staged
   bundled tree instead."
  [roots]
  (into []
        (remove #(or (contains? non-artifact-root-names (fs/file-name (str %)))
                     (extension-src-root? %)))
        roots))

(defn scan-roots
  "The trees the changed/lint/format scans walk. Extensions enter as one
   tree: extension.edn and any future layout beside src/test belong to the
   scan."
  []
  (into (module-paths) (concat (roots) [(extension-root)])))

(defn embed-paths
  "The jolt :jolt/build :embed list: module sources plus the packager roots."
  []
  (into (module-src-paths) (:jolt-embed-extra (manifest))))

(defn test-roots
  "Every tree holding test namespaces: module test dirs, the extra test
   root, and the extension test dirs."
  []
  (into (module-test-paths) (concat (test-root-paths) (extension-test-paths))))

(defn- ns-symbol
  "The namespace symbol for source file PATH under ROOT."
  [root path]
  (-> (str/replace (str path) "\\" "/")
      (str/replace (re-pattern (str "^" (java.util.regex.Pattern/quote
                                         (str/replace (str root) "\\" "/"))
                                    "/"))
                   "")
      (str/replace #"\.clj[ac]?$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn test-namespace-files
  "Every test-bearing file as {:ns SYM :file PATH :root ROOT}. A test-bearing
   file is a .clj/.cljc file whose text mentions deftest: the reader cannot know
   which alias a test macro points at, and a prose mention still loads (it just
   contributes no vars). Sorted by namespace for a stable run order."
  []
  (->> (test-roots)
       (mapcat (fn [root]
                 (->> (concat (fs/glob root "*.{clj,cljc}")
                              (fs/glob root "**/*.{clj,cljc}"))
                      (filter #(str/includes? (slurp (str %)) "deftest"))
                      (map (fn [f]
                             {:ns (ns-symbol root f)
                              :file (str f)
                              :root (str root)})))))
       (sort-by (comp str :ns))
       vec))

(defn test-registry
  "The test registry derived from the tree, split by host scoping:
   {:all [ns ...] :bb-only #{ns ...}}. :bb-only holds the namespaces under a
   :test-hosts [:bb] root. Throws when the scan comes back empty — a broken
   glob must fail the run, not silently run no tests."
  []
  (let [files (test-namespace-files)
        nss (mapv :ns files)
        roots (set (bb-only-test-roots))]
    (when (empty? nss)
      (throw (ex-info "no test namespaces found under the manifest test roots"
                      {:roots (test-roots)})))
    {:all nss
     :bb-only (into #{}
                    (keep (fn [{:keys [ns root]}]
                            (when (contains? roots root) ns)))
                    files)}))

(defn test-namespaces
  "Every test namespace, including host-scoped roots (the runner filters the
   bb-only ones where it runs). See test-registry."
  []
  (:all (test-registry)))

(defn bb-only-test-namespaces
  "The test namespaces under a :test-hosts [:bb] root — no tests on Jolt or
   the JVM, so a runner can skip requiring the tree there."
  []
  (:bb-only (test-registry)))
