(ns kmet.app.extensions.test-resources
  "Resource descriptors and extension contexts: native roots, deps lookup, the
   bundled ports, per-extension isolation, reload, and the rollback cases."
  (:require [clojure.test :as t :refer [testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [kmet.libs.host :as host]
            [kmet.ai.models :as models]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.extensions.context :as ext-context]
            [kmet.app.commands :as commands]
            [kmet.app.registry :as registry]
            [kmet.app.events :as events]
            [kmet.app.test-support :as support]
            [kmet.test-utils :refer [spit-plugin]]
            [kmet.app.skills :as skills]
            [kmet.app.tools.registry :as tools-registry]))

;; The extension host is the composition's: boot one for the namespace.
(t/use-fixtures :once support/with-composition)

;; ─── Bundled resource descriptors ───────────────────

(defn- with-extension-resources
  "Run F with io/resource answering the bundle's extensions/ keys from the
   test classpath (extensions/<x> → fixtures/<x>): artifact mode's resource
   lookup, against known fixtures."
  [f]
  (let [orig io/resource
        mapped (fn [key]
                 (let [k (str key)]
                   (if (str/starts-with? k "extensions/")
                     (orig (str "fixtures/" (subs k (count "extensions/"))))
                     (orig key))))]
    (with-redefs [io/resource mapped] (f))))

(t/deftest test-load-resource-dir-descriptor
  ;; artifact mode: a :resource-dir descriptor loads the same extension the
  ;; checkout path does, with every source read through the extensions/
  ;; prefix and the SCI backend (Phase A)
  (extensions/clear-extensions!)
  (try
    (with-extension-resources
      (fn []
        (let [result (extensions/load-extension-descriptor!
                      {:name "ext-dir" :kind :resource-dir
                       :prefix "extensions/ext-dir"
                       :path "extensions/ext-dir" :bundled? true})]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (t/is (= :sci (:loader-kind result)))
          (t/is (true? (:bundled result)))
          (t/is (some? (tools-registry/get-tool (registry/current) "multi-ext-tool")))
          (let [loaded (first (extensions/get-loaded-extensions))]
            (t/is (= "multi-ext" (:name loaded)))
            (t/is (= :resource-dir (:kind loaded)))
            (t/is (true? (:bundled loaded)))
            (t/is (nil? (:extension-dir loaded)))))))
    (finally (extensions/unload-all-extensions!))))

(t/deftest test-resource-dir-native-root-survives-resolution
  ;; The descriptor carries the host capability, but the resolved artifact is
  ;; what create-jolt-loader/forced-loader-kind receive. Dropping :native here
  ;; would leave the Phase B switch unreachable.
  (with-extension-resources
    (fn []
      (let [resolved (@#'ext-context/resolve-extension-descriptor
                      {:name "ext-dir" :kind :resource-dir
                       :prefix "extensions/ext-dir" :path "extensions/ext-dir"
                       :native "embed:extensions/ext-dir" :bundled? true})]
        (t/is (= "embed:extensions/ext-dir" (:native (:artifact resolved))))
        (t/is (= :resource-dir (:kind resolved)))))))

(t/deftest test-resource-dir-native-selects-jolt
  (if (host/jolt?)
    (with-extension-resources
      (fn []
        (let [resolved (@#'ext-context/resolve-extension-descriptor
                        {:name "ext-dir" :kind :resource-dir
                         :prefix "extensions/ext-dir" :path "extensions/ext-dir"
                         :native "embed:extensions/ext-dir" :bundled? true})]
          (t/is (= :jolt (@#'ext-context/forced-loader-kind resolved [:sci :jolt])))
          (t/is (= :sci (@#'ext-context/forced-loader-kind resolved [:sci]))
                "a sci-only bundled directory keeps its required fallback"))))
    (t/is true "skipped: embedded loader roots are Jolt-only")))

(t/deftest test-load-resource-file-descriptor
  ;; without a native target (the Babashka shape), a bundled
  ;; single file stays SCI; its extension identity is the file name (what a
  ;; user's own copy carries)
  (extensions/clear-extensions!)
  (try
    (with-extension-resources
      (fn []
        (let [result (extensions/load-extension-descriptor!
                      {:name "hello-ext" :kind :resource-file
                       :path "extensions/ext-single/hello_ext.clj" :bundled? true})]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (t/is (= :sci (:loader-kind result)))
          (t/is (some? (commands/find-command "hello-ext")))
          (t/is (= "hello_ext.clj" (:name (first (extensions/get-loaded-extensions))))))))
    (finally (extensions/unload-all-extensions!))))

(t/deftest test-resource-file-native-source-selects-jolt
  ;; The resource key is extensions/ext-single/hello_ext.clj, while the native
  ;; request is hello-ext. Resolution records that namespace and passes the
  ;; exact key to the adapter's :sources map.
  (if (host/jolt?)
    (with-extension-resources
      (fn []
        (let [resolved (@#'ext-context/resolve-extension-descriptor
                        {:name "hello-ext" :kind :resource-file
                         :path "extensions/ext-single/hello_ext.clj"
                         :native "extensions/ext-single/hello_ext.clj"
                         :bundled? true})
              artifact (:artifact resolved)]
          (t/is (= 'hello-ext (:entry-ns resolved)))
          (t/is (= 'hello-ext (:entry-ns artifact)))
          (t/is (= (:path resolved) (:path artifact)))
          (t/is (= :jolt (@#'ext-context/forced-loader-kind resolved [:sci :jolt]))))))
    (t/is true "skipped: embedded loader roots are Jolt-only")))

(t/deftest test-resource-dir-deps-lookup
  ;; a resource artifact's deps.edn is read through the same extensions/
  ;; prefix as its sources — the bundled-deps path of a built artifact (the
  ;; resolution itself is the same tools.deps pass as any extension's)
  (with-extension-resources
    (fn []
      (t/is (= {'dev.weavejester/cljfmt {:mvn/version "0.16.6"
                                         :exclusions ['rewrite-clj/rewrite-clj]}}
               (@#'ext-context/deps-of-root {:kind :resource-dir
                                             :prefix "extensions/ext-cljfmt"}))))))

(t/deftest test-duplicate-name-skipped
  ;; D11: the bundled layer ranks last — a same-name extension already
  ;; loaded (a user's own copy) is skipped, not loaded twice
  (extensions/clear-extensions!)
  (try
    (let [first-result (extensions/load-extension! "test/fixtures/ext-dir")
          second-result (extensions/load-extension! "test/fixtures/ext-dir")]
      (t/is (nil? (:error first-result)))
      (t/is (true? (:skipped second-result)))
      (t/is (= :duplicate-name (:reason second-result)))
      (t/is (= 1 (count (extensions/get-loaded-extensions)))))
    (finally (extensions/unload-all-extensions!))))

(t/deftest test-unload-removes-provider-registration
  (extensions/clear-extensions!)
  (models/load-catalogs! nil)
  (models/clear-extension-providers!)
  (let [dir "target/test-ext-prov-unload"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/prov_unload"))
    (spit (str dir "/extension.edn") "{:name \"prov-unload\" :plugin prov-unload.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/prov_unload/main.clj")
                 (str "(ns prov-unload.main\n  (:require [kmet.extension :as ext]))\n"
                      "(defn init [api]\n"
                      "  (ext/models-register-provider! api :prov-unload\n"
                      "    {:base-url \"https://prov-unload.example/v1\" :api :openai-completions\n"
                      "     :api-key \"sk-x\" :models [{:id \"pu-1\"}]}))\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (some #(= "pu-1" (:id %)) (extensions/get-all-models))
              "registered model is visible while loaded")
        (extensions/unload-all-extensions!)
        (t/is (not (some #(= "pu-1" (:id %)) (extensions/get-all-models)))
              "unload removes the provider registration")
        (t/is (nil? (extensions/get-registered-provider-config :prov-unload))))
      (finally
        (models/clear-extension-providers!)
        (fs/delete-tree dir)))))

(t/deftest test-extension-dir-is-own-directory
  ;; :extension-dir must be the extension's OWN directory — for a dir
  ;; extension :path IS the dir, so the old (fs/parent :path) was wrong and
  ;; broke :extension-dir-relative resource discovery (skills/...).
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-dir")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "dir extension: :extension-dir is the extension dir itself"
      (let [loaded (first (extensions/get-loaded-extensions))]
        (t/is (= (str (fs/canonicalize "test/fixtures/ext-dir"))
                 (str (fs/canonicalize (:extension-dir loaded))))
              (str "got: " (:extension-dir loaded)))))
    (extensions/unload-all-extensions!)))

(t/deftest test-single-file-extension-dir-is-parent
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-single/hello_ext.clj")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "single-file extension: :extension-dir is the file's parent"
      (let [loaded (first (extensions/get-loaded-extensions))]
        (t/is (= (str (fs/canonicalize "test/fixtures/ext-single"))
                 (str (fs/canonicalize (:extension-dir loaded))))
              (str "got: " (:extension-dir loaded)))))
    (extensions/unload-all-extensions!)))

(t/deftest test-unload-extension-nil-noop
  (extensions/clear-extensions!)
  ;; the load result map carries :extension (the name), not the Extension
  ;; record — passing nil to unload-extension! used to throw a cryptic
  ;; (deref nil) NPE; it must be a silent no-op
  (t/is (nil? (extensions/unload-extension! nil)))
  (let [result (extensions/load-extension! "test/fixtures/ext-single/hello_ext.clj")]
    (t/is (nil? (:error result)))
    (testing "unload still works on the real record"
      (extensions/unload-all-extensions!)
      (t/is (empty? (extensions/get-loaded-extensions))))))

(t/deftest test-load-manifest-extension-via-symlink
  ;; extension dirs are commonly installed as symlinks into a repo checkout;
  ;; strict ns-path probes resolve through the linked root or every own-file
  ;; require fails
  ;; (skipped on Windows: creating symlinks needs SeCreateSymbolicLinkPrivilege)
  (when-not (fs/windows?)
    (extensions/clear-extensions!)
    (let [link "target/ext-dir-link"]
      (fs/create-dirs "target")
      (fs/delete-if-exists link)
      (fs/create-sym-link link (fs/absolutize "test/fixtures/ext-dir"))
      (try
        (let [result (extensions/load-extension! link)]
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (testing "multi-file extension loads through a symlinked dir"
            (t/is (= "multi-ok" (:content (tools-registry/execute-tool (registry/current) "multi-ext-tool" {}))))))
        (finally
          (extensions/unload-all-extensions!)
          (fs/delete-if-exists link))))))

(t/deftest test-extension-context-has-slurp-spit
  ;; slurp/spit are absent from SCI's builtin clojure.core; the context
  ;; injects the host fns (see build-context-namespaces), so extensions
  ;; can read/write files without babashka.fs workarounds
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-slurp"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/slurp_ext"))
    (spit (str dir "/extension.edn") "{:name \"slurp-ext\" :plugin slurp-ext.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/slurp_ext/main.clj")
                 (str "(ns slurp-ext.main\n  (:require [kmet.extension :as ext]))\n"
                      "(defn init [api]\n"
                      "  (ext/register-tool! api {:name \"slurp-tool\"\n"
                      "                           :description \"reads/writes via slurp/spit\"\n"
                      "                           :execute (fn [_]\n"
                      "                                      (spit \"target/slurp-ext-out.txt\" \"written\")\n"
                      "                                      {:content (slurp \"target/slurp-ext-in.txt\")})}))\n"))
    (spit "target/slurp-ext-in.txt" "input")
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "input" (:content (tools-registry/execute-tool (registry/current) "slurp-tool" {}))))
        (t/is (= "written" (slurp "target/slurp-ext-out.txt"))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)
        (fs/delete-if-exists "target/slurp-ext-out.txt")
        (fs/delete-if-exists "target/slurp-ext-in.txt")))))

(t/deftest test-extension-gets-bundled-tools-reader-port
  ;; extensions can require clojure.tools.reader* without deps.edn pins: bb
  ;; pre-loads its reduced port and the context injects it by reference
  ;; (bundled-port-namespaces); on Jolt the extension runtime's own sci.core
  ;; require has already loaded the real tools.reader, which the native host
  ;; view shares by the same loaded-namespace rule.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-tools-reader"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/tr_ext"))
    (spit (str dir "/extension.edn") "{:name \"tr-ext\" :plugin tr-ext.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/tr_ext/main.clj")
                 (str "(ns tr-ext.main\n  (:require [kmet.extension :as ext]\n            [clojure.tools.reader :as r]\n            [clojure.tools.reader.reader-types :as rt]))\n"
                      "(defn init [api]\n"
                      "  (ext/register-tool! api {:name \"tr-tool\"\n"
                      "                           :description \"uses the bundled tools.reader port\"\n"
                      "                           :execute (fn [_]\n"
                      "                                      {:content (str (rt/read-char (rt/string-push-back-reader \"hi\"))\n"
                      "                                                     (r/read-string \"(1 2 3)\"))})}))\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "h(1 2 3)" (:content (tools-registry/execute-tool (registry/current) "tr-tool" {})))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-extension-gets-bundled-spec-port-and-file-seq
  ;; bb's clojure.spec.alpha is a bundled port bb does not preload (unlike
  ;; tools.reader), and its Maven copy fails under SCI (spec.gen.alpha's
  ;; locking2 macro expands monitor-enter, which SCI's core lacks) — the
  ;; context requires the port and injects it by reference
  ;; (spec-port-namespaces), so extensions get working clojure.spec.alpha
  ;; without deps.edn pins. file-seq is likewise absent from SCI's core and
  ;; injected with slurp/spit (cljfmt.io's FileEntity protocol needs it).
  ;; Jolt loads the Maven spec.alpha through its native loader; both hosts
  ;; now run the same fixture cleanup after loading its source.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-spec-file-seq"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/spec_ext"))
    (fs/create-dirs (str dir "/data"))
    (spit (str dir "/data/a.clj") "x")
    (spit (str dir "/data/b.clj") "y")
    (spit (str dir "/extension.edn") "{:name \"spec-ext\" :plugin spec-ext.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/spec_ext/main.clj")
                 (str "(ns spec-ext.main\n"
                      "  (:require [kmet.extension :as ext]\n"
                      "            [clojure.spec.alpha :as s]\n"
                      "            [clojure.java.io :as io]))\n"
                      "(s/def ::x int?)\n"
                      "(defn init [api]\n"
                      "  (ext/register-tool! api {:name \"spec-tool\"\n"
                      "                           :description \"uses the bundled spec port + file-seq\"\n"
                      "                           :execute (fn [_]\n"
                      "                                      {:content (str (s/valid? ::x 5)\n"
                      "                                                     \" \"\n"
                      "                                                     (count (file-seq (io/file \"target/test-ext-spec-file-seq/data\"))))})}))\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "true 3" (:content (tools-registry/execute-tool (registry/current) "spec-tool" {})))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest ^:slow test-extension-cljfmt-deps-load
  ;; regression: an extension pinning cljfmt failed to load with "Unable to
  ;; resolve symbol: monitor-enter" — the Maven spec.alpha/core.specs.alpha
  ;; jars leaked into the closure (bundled-artifact? slash-munged dots in
  ;; artifact names, missing repository/org/clojure/spec.alpha/) and failed
  ;; under SCI, while cljfmt.io's file-seq and cljfmt.config's
  ;; clojure.spec.alpha had no working source. The bundled ports are now
  ;; injected and the bundled jars excluded.
  ;;
  ;; Runs on both hosts: the fixture's deps.edn excludes rewrite-clj for the
  ;; bb port, and the fixed bundled set (kmet.app.extensions.libs) provides
  ;; it where the declaration's gap would otherwise be — the closure
  ;; assertions below stay meaningful on both (the declared cljfmt is in the
  ;; closure, the host-provided ports are not).
  (extensions/clear-extensions!)
  (let [result (extensions/load-extension! "test/fixtures/ext-cljfmt")]
    (t/is (nil? (:error result)) (str "loaded: " (:error result)))
    (testing "the closure carries the declared cljfmt, not the bundled ports"
      (let [jars (extensions/extension-jars "cljfmt-ext")]
        (t/is (some #(str/includes? % "cljfmt-0.16.6.jar") jars))
        (t/is (not-any? #(str/includes? % "spec.alpha-") jars))
        (t/is (not-any? #(str/includes? % "core.specs.alpha-") jars))
        (t/is (not-any? #(re-find #"/clojure-\\d" %) jars))))
    (testing "cljfmt works end-to-end in the extension context"
      (t/is (= "(defn foo [x]\n  (if x\n    1\n    2))"
               (:content (tools-registry/execute-tool (registry/current) "cljfmt-fmt"
                                                      {:code "(defn foo [x]\n  (if x\n   1\n   2))"})))))
    (extensions/unload-all-extensions!)))

(t/deftest test-extension-fixed-bundled-set
  ;; the fixed bundled set (kmet.app.extensions.libs; src/kmet/extension.md § Bundled
  ;; extension libraries) needs no deps.edn: a single-file extension requires
  ;; every third-party member and calls each one — same result on both hosts.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-fixed-set"
        f (str dir "/fixed_set.clj")]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (spit f
          (str/join
           "\n"
           ["(ns fixed-set-probe"
            "  (:require [kmet.extension :as ext]"
            "            [cljfmt.config :as config]"
            "            [cljfmt.core :as fmt]"
            "            [clojure.spec.alpha :as s]"
            "            [clojure.tools.reader :as r]"
            "            [edamame.core :as e]"
            "            [parinferish.core :as pf]"
            "            [rewrite-clj.zip :as z]))"
            ""
            "(defn- probe []"
            "  (let [formatted (fmt/reformat-string \"(defn f [x]\\n(+ x 1))\" config/default-config)"
            "        spec-ok (s/valid? int? 3)"
            "        read (r/read-string \"42\")"
            "        z-str (z/string (z/of-string \"(a b)\"))"
            "        edn (e/parse-string \"1 2\")"
            "        parsed (pf/parse \"(defn f [x] (+ x 1\" {:mode :indent})]"
            "    (str formatted \"|\" spec-ok \"|\" read \"|\" z-str \"|\" edn \"|\" (count parsed))))"
            ""
            "(defn init [api]"
            "  (ext/register-tool! api"
            "                      {:name \"fixed-set-tool\""
            "                       :description \"uses the fixed bundled extension set\""
            "                       :execute (fn [_] {:content (probe)})}))"
            ""
            "(def plugin {:name :ext/fixed-set"
            "             :apply (fn [_ctx api] (init api) nil)})"]))
    (try
      (let [result (extensions/load-extension! f)]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (t/is (= "(defn f [x]\n  (+ x 1))|true|42|(a b)|1|1"
                 (:content (tools-registry/execute-tool (registry/current) "fixed-set-tool" {})))))
      (finally
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-load-missing-require-error
  ;; a require the loader cannot serve must surface an actionable message,
  ;; not a bare NullPointerException (the load-fn used to call a nil
  ;; deps-resolver when the extension had no deps.edn)
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-missing-req"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/missing_req"))
    (spit (str dir "/extension.edn") "{:name \"missing-req\" :plugin missing-req.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/missing_req/main.clj")
                 "(ns missing-req.main\n  (:require [no.such.namespace]))\n(defn init [api] nil)\n")
    (let [result (extensions/load-extension! dir)]
      (t/is (str/includes? (:error result) "no.such.namespace")
            (str "actionable error, got: " (:error result)))
      (t/is (empty? (extensions/get-loaded-extensions))))
    (fs/delete-tree dir)))

(t/deftest ^:slow test-extension-lib-version-isolation
  (extensions/clear-extensions!)
  (let [ra (extensions/load-extension! "test/fixtures/ext-iso-a")
        rb (extensions/load-extension! "test/fixtures/ext-iso-b")]
    (t/is (nil? (:error ra)) (str "a: " (:error ra)))
    (t/is (nil? (:error rb)) (str "b: " (:error rb)))
    (testing "each extension serves its own declared version"
      (let [jars-a (extensions/extension-jars "iso-a")
            jars-b (extensions/extension-jars "iso-b")]
        (t/is (some #(str/includes? % "tools.cli-0.4.1.jar") jars-a))
        (t/is (some #(str/includes? % "tools.cli-1.0.206.jar") jars-b))
        (t/is (not-any? #(str/includes? % "tools.cli-1.0.206.jar") jars-a))
        (t/is (not-any? #(str/includes? % "tools.cli-0.4.1.jar") jars-b))))
    (testing "both extensions' tools work simultaneously"
      (t/is (= "iso-a" (:content (tools-registry/execute-tool (registry/current) "iso-a" {}))))
      (t/is (= "iso-b" (:content (tools-registry/execute-tool (registry/current) "iso-b" {})))))
    (testing "reload keeps each extension on its declared version"
      (extensions/unload-all-extensions!)
      (t/is (nil? (:error (extensions/load-extension! "test/fixtures/ext-iso-a"))))
      (t/is (nil? (:error (extensions/load-extension! "test/fixtures/ext-iso-b"))))
      (t/is (some #(str/includes? % "tools.cli-0.4.1.jar")
                  (extensions/extension-jars "iso-a")))
      (t/is (some #(str/includes? % "tools.cli-1.0.206.jar")
                  (extensions/extension-jars "iso-b")))
      (t/is (= "iso-a" (:content (tools-registry/execute-tool (registry/current) "iso-a" {}))))
      (t/is (= "iso-b" (:content (tools-registry/execute-tool (registry/current) "iso-b" {})))))
    (testing "unload releases both"
      (extensions/unload-all-extensions!)
      (t/is (empty? (extensions/get-loaded-extensions)))
      (t/is (nil? (tools-registry/get-tool (registry/current) "iso-a")))
      (t/is (nil? (tools-registry/get-tool (registry/current) "iso-b"))))))

(t/deftest test-load-failure-rolls-back
  (extensions/clear-extensions!)
  (testing "a file without init fails and leaves no trace"
    (let [dir "target/test-ext-bad"]
      (fs/create-dirs dir)
      (spit-plugin (str dir "/bad.clj")
                   "(ns bad-ext)\n(defn not-init [api] nil)\n")
      (let [result (extensions/load-extension! (str dir "/bad.clj"))]
        (t/is (some? (:error result)))
        (t/is (empty? (extensions/get-loaded-extensions))))
      (fs/delete-tree dir))))

(t/deftest test-a-failed-init-rolls-back-what-it-registered
  ;; The rollback runs the same unload path, so what the plugin body registered
  ;; before throwing is disposed with the extension's owner —
  ;; no command, tool or skill outlives a failed load.
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-init-rollback"]
    (fs/delete-tree dir)
    (fs/create-dirs (str dir "/rb_ext"))
    (spit (str dir "/extension.edn")
          "{:name \"rb-ext\" :plugin rb-ext.main/plugin :loader [:sci :jolt]}\n")
    (spit-plugin (str dir "/rb_ext/main.clj")
                 (str "(ns rb-ext.main\n  (:require [kmet.extension :as ext]))\n"
                      "(defn init [api]\n"
                      "  (ext/register-command! api {:name \"rb-cmd\" :description \"d\"\n"
                      "                              :handler (fn [_ _] nil)})\n"
                      "  (ext/register-tool! api {:name \"rb-tool\" :label \"T\"\n"
                      "                           :description \"d\" :parameters []\n"
                      "                           :execute (fn [_] {:content \"ok\"})})\n"
                      "  (ext/register-skill! api \"---\\nname: rb-skill\\ndescription: d\\n---\\nbody\")\n"
                      "  (throw (ex-info \"init failed\" {})))\n"))
    (try
      (let [result (extensions/load-extension! dir)]
        (t/is (some? (:error result)) "the load fails")
        (t/is (empty? (extensions/get-loaded-extensions)))
        (t/is (nil? (commands/find-command "rb-cmd")) "the command was rolled back")
        (t/is (nil? (tools-registry/get-tool (registry/current) "rb-tool")) "the tool was rolled back")
        (t/is (nil? (skills/get-skill "rb-skill")) "the skill was rolled back")
        (t/is (empty? (registry/by-owner (registry/current) :kmet/command "rb-cmd"))
              "and the owner holds nothing"))
      (finally
        (extensions/clear-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-extension-contexts-are-forked-and-isolated
  ;; the SCI backend builds the shared layers once and forks that base per
  ;; extension (kmet.loader.sci-loader :base); an extension's own namespaces
  ;; and defs stay in its fork — two extensions may even declare the same
  ;; namespace name without seeing each other. SCI-only: the Jolt native
  ;; backend keeps its own per-context world (see loader.md §9).
  (when-not (host/jolt?)
    (extensions/clear-extensions!)
    (let [dir "target/test-ext-fork-iso"]
      (fs/delete-tree dir)
      (fs/create-dirs dir)
      (spit-plugin (str dir "/iso_a.clj")
                   (str "(ns fork-iso (:require [kmet.extension :as ext]))\n"
                        "(def tag :a)\n"
                        "(defn init [api]\n"
                        "  (ext/register-tool! api {:name \"fork-iso-a\" :description \"a\"\n"
                        "                           :execute (fn [_] {:content (name tag)})}))\n"))
      (spit-plugin (str dir "/iso_b.clj")
                   (str "(ns fork-iso (:require [kmet.extension :as ext]))\n"
                        "(def tag :b)\n"
                        "(defn init [api]\n"
                        "  (ext/register-tool! api {:name \"fork-iso-b\" :description \"b\"\n"
                        "                           :execute (fn [_] {:content (name tag)})}))\n"))
      (try
        (t/is (nil? (:error (extensions/load-extension! (str dir "/iso_a.clj")))))
        (t/is (nil? (:error (extensions/load-extension! (str dir "/iso_b.clj")))))
        (t/is (= "a" (:content (tools-registry/execute-tool (registry/current) "fork-iso-a" {})))
              "each fork sees its own def")
        (t/is (= "b" (:content (tools-registry/execute-tool (registry/current) "fork-iso-b" {}))))
        (finally
          (extensions/unload-all-extensions!)
          (fs/delete-tree dir))))))

(t/deftest test-extension-context
  (testing "headless default context is complete and callable"
    (let [ctx (extensions/build-extension-context)]
      (t/is (= :print (:mode ctx)))
      (t/is (false? (:has-ui ctx)))
      (t/is (string? (:cwd ctx)))
      (t/is (nil? (:model ctx)))
      (t/is (= [] (:scoped-models ctx)))
      (t/is (map? (:models ctx)) "read-only registry facades (pi ctx.modelRegistry)")
      (t/is (vector? ((:get-all (:models ctx)))))
      (t/is (nil? ((:find (:models ctx)) :no-provider "no-model")))
      (t/is (true? ((:is-idle ctx))))
      (t/is (false? ((:has-pending-messages ctx))))
      (t/is (nil? ((:signal ctx))))
      (t/is (nil? ((:get-context-usage ctx))))
      (t/is (nil? ((:get-system-prompt ctx))))
      (t/is (nil? ((:wait-for-idle ctx))))
      (t/is (= {:cancelled true} ((:new-session ctx))))
      (t/is (= {:cancelled true} ((:fork ctx) "e1")))
      (t/is (= {:cancelled true} ((:navigate-tree ctx) "e1")))
      (t/is (= {:cancelled true} ((:switch-session ctx) "/x")))
      (t/is (false? ((:is-project-trusted ctx))))
      (t/is (nil? ((:get-name (:session ctx))))
            "ctx carries the read-only session facade (pi: ctx.sessionManager)")))
  (testing "event handlers receive (event ctx) — fixed arity-2 contract"
    (let [calls (atom [])
          w (extensions/wrap-event-handler
             (fn [ev ctx] (swap! calls conj [(:type ev) (:mode ctx)])))]
      (w {:type :agent-start})
      (t/is (= [[:agent-start :print]] @calls))
      (t/is (thrown? clojure.lang.ArityException
                     ((extensions/wrap-event-handler (fn [_] :legacy))
                      {:type :agent-end}))
            "arity-1 handlers fail fast — no legacy shim"))))

(t/deftest test-extension-ctx-events-via-bus
  (testing "sci handlers receive the ctx through the real bus path"
    (extensions/clear-extensions!)
    (let [dir "target/test-ext-ctx-events"
          probes (atom [])
          unsub-a (support/on-event :session-info-changed
                                    (fn [ev] (swap! probes conj [:a ev])))
          unsub-b (support/on-event :queue-update
                                    (fn [ev] (swap! probes conj [:b (:mode ev)])))
          content (str "(ns ctx-events (:require [kmet.extension :as ext]))\n"
                       "(defn init [api]\n"
                       "  (ext/register-command! api {:name \"probe-cmd\"\n"
                       "                                :description \"pc\"\n"
                       "                                :handler (fn [c a] nil)})\n"
                       "  (ext/on-event api :session-start\n"
                       "    (fn [ev ctx] (ext/emit-event! api {:type :session-info-changed\n"
                       "                                        :from (:type ev)\n"
                       "                                        :cmds (ext/get-commands api)})))\n"
                       "  (ext/on-event api :session-tree\n"
                       "    (fn [ev ctx] (ext/emit-event! api {:type :queue-update :mode (:mode ctx)}))))\n")]
      (fs/create-dirs dir)
      (spit-plugin (str dir "/ext.clj") content)
      (let [result (extensions/load-extension! (str dir "/ext.clj"))]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (events/emit! (support/composition-ctx) {:type :session-start})
        (events/emit! (support/composition-ctx) {:type :session-tree :new-leaf-id "x"})
        (let [[[_ probe-a] [_ mode-b]] @probes]
          (t/is (= :session-start (:from probe-a))
                "arity-2 handler received the event")
          (t/is (= :print mode-b) "arity-2 handler got the ctx")
          (testing "get-commands is sanitized — handlers stay private"
            (t/is (seq (:cmds probe-a)))
            (t/is (some #(= "probe-cmd" (:name %)) (:cmds probe-a))
                  "the extension's own command is visible")
            (t/is (every? #(and (string? (:name %)) (string? (:description %)))
                          (:cmds probe-a)))
            (t/is (every? #(not (contains? % :handler)) (:cmds probe-a)))
            (t/is (every? #(not (contains? % :extension-handler)) (:cmds probe-a))))))
      (extensions/unload-all-extensions!)
      (unsub-a)
      (unsub-b)
      (fs/delete-tree dir))))
(t/deftest test-extension-command-ctx-dispatch
  ;; (Previously a top-level testing block, so its
  ;; assertions ran at namespace load instead of as a test.)
  (testing "extension commands carry :extension-handler for ctx dispatch"
    (extensions/clear-extensions!)
    (let [dir "target/test-ext-ctx-cmd"]
      (fs/create-dirs dir)
      (spit-plugin (str dir "/ext.clj")
                   (str "(ns ctx-cmd (:require [kmet.extension :as ext]))\n"
                        "(defn init [api]\n"
                        "  (ext/register-command! api\n"
                        "    {:name \"ctx-test\" :description \"d\"\n"
                        "     :handler (fn [ctx args] {:mode (:mode ctx) :args args})}))\n"))
      (let [result (extensions/load-extension! (str dir "/ext.clj"))]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))
        (let [c (commands/find-command "ctx-test")]
          (t/is (fn? (:extension-handler c)))
          (t/is (= {:mode :print :args "hi"}
                   ((:extension-handler c) (extensions/build-extension-context) "hi"))))
        (extensions/unload-all-extensions!)
        (fs/delete-tree dir)))))

(t/deftest test-extension-namespace-isolation
  (extensions/clear-extensions!)
  (let [load (fn [name content]
               (let [dir (str "target/test-ext-iso-" name)]
                 (fs/create-dirs dir)
                 (spit-plugin (str dir "/ext.clj") content)
                 (let [result (extensions/load-extension! (str dir "/ext.clj"))]
                   (fs/delete-tree dir)
                   ;; unload the single-file probe so each testing block starts
                   ;; clean (bb tolerates same-path reloads; Jolt's runner runs
                   ;; all vars on one future where residue breaks later vars)
                   (extensions/unload-all-extensions!)
                   result)))]
    (testing "a manifest-dir extension's own internal namespaces load
              regardless of prefix (strict ns-path lookup)"
      (let [dir "target/test-ext-internal-ns"]
        (fs/create-dirs (str dir "/kmet/extensions/myext"))
        (spit (str dir "/extension.edn")
              "{:name \"internal-ns\" :plugin kmet.extensions.myext.core/plugin :loader [:sci :jolt]}\n")
        (spit-plugin (str dir "/kmet/extensions/myext/core.clj")
                     (str "(ns kmet.extensions.myext.core\n"
                          "  (:require [kmet.extension :as ext]\n"
                          "            [kmet.extensions.myext.internal :as internal]))\n"
                          "(defn init [api]\n"
                          "  (when-not (= 42 (internal/answer))\n"
                          "    (throw (ex-info \"internal require failed\" {}))))\n"))
        (spit (str dir "/kmet/extensions/myext/internal.clj")
              "(ns kmet.extensions.myext.internal)\n(defn answer [] 42)\n")
        (let [result (extensions/load-extension! dir)]
          (fs/delete-tree dir)
          (t/is (nil? (:error result)) (str "loaded: " (:error result)))
          (extensions/unload-all-extensions!))))

    (testing "app-namespace requires are rejected with an actionable error"
      (let [result (load "app" "(ns bad-app (:require [kmet.app.commands :as c]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             (str "may depend only on kmet.extension, kmet.tui.*,"
                                  " kmet.libs.*, kmet.reakt.*, kmet.hikap.* and kmet.srce.core")))))
    (testing "the loader is host machinery — not shared, not requireable"
      (let [result (load "loader-ns"
                         (str "(ns bad-loader (:require [kmet.loader.core :as ldr]))\n"
                              "(defn init [api] nil)\n"))]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result) "host machinery")
              (str "got: " (:error result))))
      ;; a *top-level* require evaluates in the extension's own context;
      ;; unlike a host-called init's require (which follows the ambient
      ;; host context, loader.md §3), this proves the loader is absent
      ;; from the injected share map — an injected namespace would
      ;; resolve and the require would succeed
      (let [result (load "loader-top"
                         (str "(ns loader-top (:require [kmet.extension :as ext]))\n"
                              "(require 'kmet.loader.core)\n"
                              "(defn init [api] nil)\n"))]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result) "host machinery")
              (str "got: " (:error result)))))
    (testing "a kmet.tui.* typo is rejected (sci would NPE silently)"
      (let [result (load "typo" "(ns bad-typo (:require [kmet.tui.theem :as t]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             "not part of the kmet.tui.* library shared with extensions"))))
    (testing "a shared-library typo is rejected (sci would NPE silently)"
      (let [result (load "lib-typo" "(ns bad-lib-typo (:require [kmet.libs.hashh :as h]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result)
                             "not part of the shared library layers"))))
    (testing "valid kmet.tui.* requires load and share the real library"
      (let [result (load "tui" "(ns good-tui\n  (:require [kmet.tui.components.text :as text]\n            [kmet.tui.protocols :as protocols]))\n(defn init [api]\n  (let [c (text/make-text \"hi\")]\n    (when-not (vector? (protocols/render c 20))\n      (throw (ex-info \"render failed\" {})))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "valid kmet.libs.* requires load and share the real library"
      (let [result (load "lib" "(ns good-lib\n  (:require [kmet.libs.host :as host]\n            [kmet.libs.yaml :as yaml]))\n(defn init [api]\n  (let [s (host/runtime-name)]\n    (when-not (string? s)\n      (throw (ex-info \"host failed\" {})))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "kmet.libs.archive loads from SCI and extracts zips (no ZipFile interop in extensions)"
      (let [result (load "archive" (str "(ns good-archive\n  (:require [babashka.fs :as fs]\n"
                                        "            [clojure.java.io :as io]\n"
                                        "            [kmet.libs.archive :as archive]))\n"
                                        "(defn init [_api]\n"
                                        "  (let [tmpdir (or (System/getenv \"TMPDIR\") (System/getProperty \"java.io.tmpdir\"))\n"
                                        "        zip (str (fs/path tmpdir \"arc-test.zip\"))\n"
                                        "        out (str (fs/path tmpdir \"arc-out\"))]\n"
                                        "    (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream zip))]\n"
                                        "      (.putNextEntry zos (java.util.zip.ZipEntry. \"a.txt\"))\n"
                                        "      (.write zos (.getBytes \"hi\" \"UTF-8\"))\n"
                                        "      (.closeEntry zos))\n"
                                        "    (let [extracted (archive/extract-zip! zip out)]\n"
                                        "      (when-not (= \"hi\" (slurp (str (first extracted))))\n"
                                        "        (throw (ex-info \"archive failed\" {}))))))\n"))]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "the whitelisted built-in renderer namespace loads and shares direct vars"
      (let [result (load "renderer" "(ns good-renderer\n  (:require [kmet.ui.tui.tool-renderers :as renderers]))\n(defn init [_api]\n  (when-not (fn? renderers/render-edit-call)\n    (throw (ex-info \"renderer failed\" {}))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "kmet.libs.http loads and works from SCI (the outbound-HTTP boundary)"
      (let [result (load "http-lib" "(ns good-http-lib\n  (:require [kmet.libs.http :as http]))\n(defn init [api]\n  (when-not (fn? http/request)\n    (throw (ex-info \"http failed\" {}))))\n")]
        (t/is (nil? (:error result)) (str "loaded: " (:error result)))))
    (testing "direct babashka.http-client requires are rejected with an actionable error"
      (let [result (load "http-direct" "(ns bad-http-direct\n  (:require [babashka.http-client :as http]))\n(defn init [api] nil)\n")]
        (t/is (some? (:error result)))
        (t/is (str/includes? (:error result) "kmet.libs.http")
              "the rejection names the proxy-aware boundary")))))

(t/deftest ^:slow test-extension-bad-deps-fails-load
  (extensions/clear-extensions!)
  (let [dir "target/test-ext-bad-deps"]
    (fs/create-dirs dir)
    (spit (str dir "/extension.edn") "{:name \"bad-deps\" :plugin bad-deps.main/plugin :loader [:sci :jolt]}\n")
    (spit (str dir "/deps.edn") "{:deps {org.clojure/does-not-exist {:mvn/version \"9.9.9\"}}}\n")
    (fs/create-dirs (str dir "/bad_deps"))
    (spit-plugin (str dir "/bad_deps/main.clj")
                 "(ns bad-deps.main (:require [org.clojure.does-not-exist :as bad]))\n(defn init [api] nil)\n")
    (testing "an unresolvable dep fails the load without killing the process"
      (let [result (extensions/load-extension! dir)]
        (t/is (some? (:error result)))
        (t/is (empty? (extensions/get-loaded-extensions)))))
    (fs/delete-tree dir)))

(t/deftest test-reload-extensions
  (testing "reload-extensions! (container dirs) unloads + reloads"
    (let [container (str "target/test-ext-container-" (System/currentTimeMillis))]
      (fs/create-dirs container)
      (fs/copy-tree "test/fixtures/ext-single" container)
      (fs/copy-tree "test/fixtures/ext-dir" (str container "/multi-ext"))
      (extensions/clear-extensions!)
      (let [results (extensions/reload-extensions! [container])]
        (t/is (= 2 (count (filter #(nil? (:error %)) results)))
              (str "both loaded: " (pr-str results)))
        (t/is (some? (commands/find-command "hello-ext")))
        (t/is (some? (tools-registry/get-tool (registry/current) "multi-ext-tool")))
        (testing "reload again: old state fully replaced, no duplicates"
          (extensions/reload-extensions! [container])
          (t/is (= 1 (count (filter #(= "hello_ext.clj" (:name %))
                                    (extensions/get-loaded-extensions))))
                "no duplicate extensions after reload")
          (t/is (some? (commands/find-command "hello-ext")))))
      (extensions/unload-all-extensions!)
      (fs/delete-tree container))))
