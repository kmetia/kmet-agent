(ns kmet.loader.test-jolt-loader
  "kmet.loader.jolt-loader's adapter tests — Jolt-only.

   The adapter is a `.jolt` source — the runtime's own extension — so bb and
   the JVM cannot load it at all; every test here skips there, and under
   `jolt test` they exercise the native backend through the *portable*
   protocol: a dashed
   namespace loading from a root, a var request answered through a context's
   namespace link, the host view the extension contract is built on, unload,
   and the ambient resource path extension code relies on."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest]]
            [kmet.extension]
            [kmet.loader.core :as loader]))

(defn- adapter
  "The adapter's public constructors, or nil on bb/JVM (it is a .jolt source
   there). Resolved at runtime so this test namespace compiles on both
   hosts."
  []
  (when (boolean (find-var 'clojure.core/*jolt-version*))
    (require 'kmet.loader.jolt-loader)
    (let [v (fn [s] (some-> (ns-resolve 'kmet.loader.jolt-loader s) deref))]
      {:root (v 'root)
       :classpath (v 'classpath)
       :host-view (v 'host-view)
       :with-loader* (v 'with-loader*)
       :unloaded? (v 'unloaded?)
       :status (v 'status)})))

(defn- dir!
  [name]
  (let [d (str "target/test-jolt-loader/" name)]
    (fs/delete-tree d)
    (fs/create-dirs d)
    d))

(defn- threw
  [f]
  (try
    (f)
    nil
    (catch Throwable e e)))

;;; The Jolt root behind the adapter refuses too (the runtime's own rule, loader.ss):
;; kmet's portable root refuses in its unload!; this is the other half of the
;; same contract — the host's global world is not a disposable context.
(deftest adapter-root-is-not-unloadable
  (if-let [{:keys [root]} (adapter)]
    (let [r (root)
          e (threw #(loader/unload! r))]
      (t/is (some? e) "unload! on the host root is refused")
      (t/is (false? (loader/unloaded? r)) "and the root is still live")
      (t/is (some? (loader/find r {:kind :ns :name "clojure.string"}))))
    (t/testing "bb/JVM: the adapter is a .jolt source" (t/is true))))

(deftest adapter-loads-a-dashed-namespace
  (if-let [{:keys [classpath status]} (adapter)]
    (let [d (dir! "dashed")]
      (fs/create-dirs (str d "/lib_one"))
      (spit (str d "/lib_one/core.clj") "(ns lib-one.core) (defn f [] :dashed) (def v 7)")
      (let [l (classpath [d] {:id "test:dashed"})]
        (t/is (= "test:dashed" (:loader-id (status l))))
        (t/is (seq (loader/find l {:kind :ns :name "lib-one.core"}))
              "the munged path (dashed ns, underscored file) locates")
        (loader/load l {:kind :ns :name "lib-one.core"})
        (t/is (= :dashed ((loader/load l {:kind :var :name "lib-one.core/f"})))
              "a var request loads through the namespace link")
        (t/is (= 7 (deref (loader/resolve l {:kind :var :name "lib-one.core/v"}))))
        (fs/delete-tree d)))
    (t/is true "skipped: the adapter is Jolt-only")))

(deftest adapter-host-view-is-the-extension-contract
  (if-let [{:keys [root host-view]} (adapter)]
    (let [host (host-view (root) #{'kmet.extension 'kmet.libs})]
      (t/is (identical? (ns-resolve 'kmet.extension 'register-tool!)
                        (loader/load host {:kind :var :name "kmet.extension/register-tool!"}))
            "a shared host namespace comes back by reference")
      (t/is (= [] (loader/find host {:kind :ns :name "kmet.app.commands"}))
            "a name outside the contract is invisible")
      (t/is (= :loader/miss
               (:type (ex-data (threw #(loader/load host {:kind :ns :name "kmet.app.commands"})))))
            "invisible means a miss, not a denial — a context's own roots get their turn")
      (t/is (str/includes? (str (ex-message (threw #(loader/find host {:kind :ns :name "kmet.loader.core"}))))
                           "host machinery")
            "the loader's own namespace is rejected with the actionable error")
      (t/is (some? (loader/load host {:kind :resource :name "kmet/loader/loader.md"}))
            "resources pass through: the contract restricts namespaces, not the host's resources"))
    (t/is true "skipped: the adapter is Jolt-only")))

(deftest adapter-unload-closes-the-loader
  (if-let [{:keys [classpath unloaded?]} (adapter)]
    (let [d (dir! "unload")]
      (spit (str d "/app.clj") "(ns app) (defn f [] :app)")
      (let [l (classpath [d] {:id "test:unload"})]
        (loader/load l {:kind :ns :name "app"})
        (let [cell (loader/load l {:kind :var :name "app/f"})]
          (t/is (false? (unloaded? l)))
          (t/is (= :app (cell)))
          (let [rep (loader/unload! l)]
            (t/is (true? (:unloaded rep)))
            (t/is (true? (unloaded? l)))
            (t/is (= :loader/unloaded
                     (:type (ex-data (threw #(loader/find l {:kind :ns :name "app"})))))
                  "a closed loader refuses new work")
            (t/is (= :app (cell))
                  "definitions already resolved stay live"))))
      (fs/delete-tree d))
    (t/is true "skipped: the adapter is Jolt-only")))

(deftest adapter-ambient-tccl
  ;; TCCL is where a library that finds its own resources the Java way looks:
  ;; inside a context it must be that context's classloader, not the host's
  ;; (jolt.loader conformance case 22; kmet-side, this is what makes a native
  ;; extension dependency's (.getResource (.getContextClassLoader ...)) land in
  ;; the extension's own roots).
  (if-let [{:keys [classpath with-loader*]} (adapter)]
    (let [d (dir! "tccl")]
      (spit (str d "/ctx-res.edn") "{:ctx true}")
      (let [l (classpath [d] {:id "test:tccl"})
            outside (.getContextClassLoader (Thread/currentThread))
            inside (with-loader* l (fn [] (.getContextClassLoader (Thread/currentThread))))]
        (t/is (not (identical? outside inside))
              "inside with-loader* the TCCL is the context's, not the host's")
        (t/is (some? (.getResource inside "ctx-res.edn"))
              "and a library finding its resources the Java way lands in the
               context's own roots (jolt.loader conformance case 22)"))
      (fs/delete-tree d))
    (t/is true "skipped: the adapter is Jolt-only")))

(deftest adapter-ambient-resources
  (if-let [{:keys [classpath with-loader*]} (adapter)]
    (let [d (dir! "res")]
      (spit (str d "/ctx-res.edn") "{:ctx true}")
      (let [l (classpath [d] {:id "test:res"})]
        (t/is (nil? (io/resource "ctx-res.edn")) "the file is not on the host's roots")
        (t/is (some? (with-loader* l (fn [] (io/resource "ctx-res.edn"))))
              (str "inside with-loader* the ambient loader answers it — what"
                   " extension callbacks rely on for their own bundled files")))
      (fs/delete-tree d))
    (t/is true "skipped: the adapter is Jolt-only")))

(deftest adapter-probes-embedded-loader-roots
  ;; The public adapter probe is the migration gate for bundled resource
  ;; directories: false on Jolt releases predating jolt.loader/embedded-root?,
  ;; true once the feature exists. A binary --smoke gate requires the true
  ;; branch, but source tests remain valid on the older runtime.
  (if (boolean (find-var 'clojure.core/*jolt-version*))
    (let [probe (requiring-resolve 'kmet.loader.jolt-loader/embedded-roots?)]
      (t/is (boolean? (probe)))
      (t/is (= (some? (resolve 'jolt.loader/embedded-root?)) (probe))))
    (t/is true "skipped: the adapter is Jolt-only")))
