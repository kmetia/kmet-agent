(ns kmet.tasks.build-test
  ;; Every test here exercises the bb-only packaging pipeline (kmet.tasks.build:
  ;; uberjar/pack-extension over babashka.classpath + java.util.zip — the bb
  ;; branch of the `dist` task). All vars carry ^:bb-only — kmet.tasks.runner runs
  ;; them under bb and skips them on the jolt host, whose packager is
  ;; kmet.tasks.build-jolt (see kmet.tasks.build-jolt-test).
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.version :as version-lib]
            [kmet.tasks.build :as build]))

(deftest ^:bb-only platform-for-names-os-and-arch
  (is (= "linux-aarch64" (build/platform-for "linux" "aarch64")))
  (is (= "linux-aarch64" (build/platform-for "Linux" "arm64")))
  (is (= "linux-amd64" (build/platform-for "Linux" "amd64")))
  (is (= "macos-aarch64" (build/platform-for "Mac OS X" "aarch64")))
  (is (= "macos-amd64" (build/platform-for "Darwin" "x86_64")))
  (is (= "windows-amd64" (build/platform-for "Windows 11" "amd64")))
  (testing "unsupported combos have no platform"
    (is (nil? (build/platform-for "SunOS" "sparc")))
    (is (nil? (build/platform-for "Linux" "riscv64")))))

(deftest ^:bb-only normalize-target-reads-platforms-and-assets
  ;; targets are dist platforms; the babashka release asset behind a platform
  ;; (the names differ for linux) is accepted too
  (is (= "linux-aarch64" (build/normalize-target "linux-aarch64")))
  (is (= "linux-aarch64" (build/normalize-target "linux-aarch64-static")))
  (is (= "linux-amd64" (build/normalize-target "linux-amd64")))
  (is (= "linux-amd64" (build/normalize-target "linux-amd64-static")))
  (is (= "windows-amd64" (build/normalize-target "windows-amd64")))
  (is (nil? (build/normalize-target "atari-2600"))))

(deftest ^:bb-only every-published-target-is-a-platform
  ;; the table is keyed by dist platform, not by release asset slug — the
  ;; artifact name is kmet-<ver>-bb<bb-ver>-<platform>
  (let [targets @#'build/target-table]
    (doseq [platform (sort (keys targets))]
      (is (= platform (build/normalize-target platform)))
      (is (string? (:asset (get targets platform)))))))

(deftest ^:bb-only parse-args-collects-targets-and-flags
  (is (= {:targets [] :all? false :force? false :no-smoke? true :test? false :help? false}
         (build/parse-args [])))
  (is (= {:targets ["linux-aarch64"] :all? true :force? true :no-smoke? true
          :test? false :help? false}
         (build/parse-args ["linux-aarch64" "--all" "--force"])))
  (is (= {:targets ["macos-aarch64" "windows-amd64"]
          :all? false :force? false :no-smoke? true :test? false :help? true}
         (build/parse-args ["macos-aarch64" "--help" "windows-amd64"])))
  (testing "a babashka release asset slug is accepted as its platform"
    (is (= {:targets ["linux-amd64"] :all? false :force? false :no-smoke? true
            :test? false :help? false}
           (build/parse-args ["linux-amd64-static"]))))
  (testing "the smoke test is opt-in"
    (is (false? (:no-smoke? (build/parse-args ["--smoke"]))))
    (is (true? (:no-smoke? (build/parse-args ["--no-smoke"])))))
  (testing "--test builds the test-runner artifact"
    (is (true? (:test? (build/parse-args ["--test"]))))
    (is (true? (:test? (build/parse-args ["linux-aarch64" "--test"]))))))

(deftest ^:bb-only parse-args-rejects-bad-input
  (is (thrown-with-msg? Exception #"unknown target"
                        (build/parse-args ["plan9"])))
  (is (thrown-with-msg? Exception #"unknown option"
                        (build/parse-args ["--wat"]))))

(deftest ^:bb-only version-is-the-artifact-form-of-the-checkout-version
  ;; the rule (and its describe shapes) is kmet.libs.test-version's; the
  ;; packager names artifacts with its v-stripped form
  (with-redefs [version-lib/checkout-version (constantly "v1.2.3-4-gabc1234")]
    (is (= "1.2.3-4-gabc1234" (build/version))))
  (with-redefs [version-lib/checkout-version (constantly "dev-g0a1b2c3")]
    (is (= "dev-g0a1b2c3" (build/version))))
  (with-redefs [version-lib/checkout-version (constantly "dev")]
    (is (= "dev" (build/version)))))

(deftest ^:bb-only artifact-base-includes-bb-version-before-platform
  (is (= "kmet-1.2.3-bb1.13.219-linux-aarch64"
         (build/artifact-base "1.2.3" "1.13.219" "linux-aarch64")))
  (is (= "kmet-20260903-abc1234-bb1.13.219-windows-amd64"
         (build/artifact-base "20260903-abc1234" "1.13.219" "windows-amd64")))
  (testing "a --test build is named kmet-test-*"
    (is (= "kmet-test-1.2.3-bb1.13.219-linux-aarch64"
           (build/artifact-base "1.2.3" "1.13.219" "linux-aarch64" {:test? true})))))

(deftest ^:bb-only generate-test-main-requires-the-suite-under-jolt
  (let [f (build/generate-test-main!)
        text (slurp (str f))
        nss (var-get (requiring-resolve 'kmet.tasks.runner/all-namespaces))]
    (is (fs/regular-file? f))
    (testing "the static requires are jolt-only: babashka keeps the tolerant dynamic load"
      (is (str/includes? text "#?@(:jolt ["))
      (is (str/includes? text ":default []")))
    (testing "every registered test namespace is statically required"
      (is (every? #(str/includes? text (str %)) nss)))
    (testing "the entry dispatches both suites onto the runner"
      (is (str/includes? text "--test\") (apply runner/-main false"))
      (is (str/includes? text "--test-ext\") (apply runner/-main true")))))

(deftest ^:bb-only test-uberjar-carries-the-suite-and-the-runner-main
  (let [jar (build/test-uberjar*)]
    (is (fs/regular-file? jar))
    (with-open [zf (java.util.zip.ZipFile. (fs/file jar))]
      (let [entries (set (map (fn [e] (.getName ^java.util.zip.ZipEntry e))
                              (enumeration-seq (.entries zf))))
            manifest (with-open [in (.getInputStream zf (.getEntry zf "META-INF/MANIFEST.MF"))]
                       (slurp in))]
        (testing "the jar has the generated entry, the runner and the tests"
          (is (contains? entries "kmet/tasks/test_main.clj"))
          (is (contains? entries "kmet/tasks/runner.clj"))
          (is (contains? entries "kmet/libs/test_num.clj"))
          (is (contains? entries "kmet/core.clj")))
        (testing "the entry point is the test runner"
          (is (str/includes? manifest "Main-Class: kmet.tasks.test-main")))))))

(deftest ^:bb-only test-classes-are-only-in-a-test-build
  ;; the app artifact is src/ and only src/ — test/ and tasks/ (the runner and
  ;; the generated entry) exist solely in a --test build
  (let [app (build/uberjar*)]
    (with-open [zf (java.util.zip.ZipFile. (fs/file app))]
      (let [entries (set (map (fn [e] (.getName ^java.util.zip.ZipEntry e))
                              (enumeration-seq (.entries zf))))]
        (is (contains? entries "kmet/core.clj"))
        (is (not-any? #(str/starts-with? % "kmet/tasks/") entries))
        (is (not-any? #(str/starts-with? % "kmet/libs/test_") entries))
        (is (not-any? #(str/starts-with? % "kmet/test_") entries))
        (is (not (contains? entries "kmet/tasks/test_main.clj")))))))

(deftest ^:bb-only extract-archive-zip-slip-guard
  ;; The containment check must reject entries that escape the destination
  ;; dir and accept legitimate ones (regression: the guard was inverted —
  ;; fs/starts-with? takes (path prefix) — so the real bb.exe release zip
  ;; was rejected with ::zip-slip while "../evil" style entries were let
  ;; through, writing outside the destination). canonicalize resolves ".."
  ;; lexically so the check is effective.
  (let [tmp (str (fs/create-dirs "target/test-build-extract") "")]
    (try
      (let [make-zip! (fn [entry]
                        (let [zip (str (fs/path tmp "evil.zip"))]
                          (with-open [zos (java.util.zip.ZipOutputStream.
                                           (io/output-stream (fs/file zip)))]
                            (.putNextEntry zos (java.util.zip.ZipEntry. entry))
                            (.write zos (.getBytes "x" "UTF-8"))
                            (.closeEntry zos))
                          zip))
            extract! (fn [entry]
                       (@#'build/extract-archive!
                        {:ext :zip :bin-name "bb.exe"}
                        (make-zip! entry)
                        (fs/path tmp (str "dest-" (count entry)))))]
        (testing "legitimate top-level entries extract"
          (is (fs/exists? (extract! "bb.exe")) "bb.exe lands in dest"))
        (testing "escape attempts are rejected before writing"
          (doseq [entry [(str ".." (char 92) "evil")
                         (str "sub" (char 92) ".." (char 92) ".." (char 92) "evil")]]
            (let [dest (fs/path tmp (str "dest-" (count entry)))]
              (fs/create-dirs dest)
              (is (thrown-with-msg? Exception #"zip entry escapes target dir"
                                    (extract! entry))
                  (str "entry " (pr-str entry) " throws ::zip-slip"))
              (is (empty? (filter #(not (fs/directory? %)) (fs/list-dir dest)))
                  (str "no file written for " (pr-str entry)))))))
      (finally
        (fs/delete-tree tmp)))))

(deftest ^:bb-only pack-extension-verifies-and-packs
  (testing "packs the real clojure artifact root"
    (let [out "target/test-pack-clojure.jar"]
      (fs/delete-if-exists out)
      (is (= out (build/pack-extension! "extensions/clojure/src" out)))
      (is (fs/regular-file? out))
      (fs/delete-if-exists out)))
  (testing "rejects a root without extension.edn"
    (let [dir "target/test-pack-no-manifest"]
      (fs/create-dirs dir)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no extension.edn"
                            (build/pack-extension! dir "target/test-pack-no.jar")))
      (fs/delete-tree dir)))
  (testing "rejects strict-layout violations"
    (let [dir "target/test-pack-sloppy"]
      (fs/create-dirs (str dir "/sloppy"))
      (spit (str dir "/extension.edn") "{:name \"sloppy\" :entry sloppy.main :loader [:sci]}")
      (spit (str dir "/sloppy/main.clj") "(ns wrong.place)\n(defn init [api] nil)\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strict layout violation"
                            (build/pack-extension! dir "target/test-pack-sloppy.jar")))
      (fs/delete-tree dir)))
  (testing "rejects string :entry manifests"
    (let [dir "target/test-pack-strentry"]
      (fs/create-dirs dir)
      (spit (str dir "/extension.edn") "{:name \"strentry\" :entry \"main.clj\" :loader [:sci]}")
      (spit (str dir "/main.clj") "(ns strentry.main)\n(defn init [api] nil)\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":entry"
                            (build/pack-extension! dir "target/test-pack-str.jar")))
      (fs/delete-tree dir)))
  (testing "rejects a manifest without :loader and unknown loader kinds"
    (let [dir "target/test-pack-loader"]
      (fs/create-dirs (str dir "/ld"))
      (spit (str dir "/ld/main.clj") "(ns ld.main)\n(defn init [api] nil)\n")
      (spit (str dir "/extension.edn") "{:name \"ld\" :entry ld.main}")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":loader"
                            (build/pack-extension! dir "target/test-pack-loader.jar")))
      (spit (str dir "/extension.edn") "{:name \"ld\" :entry ld.main :loader [:jvm]}")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":loader"
                            (build/pack-extension! dir "target/test-pack-loader.jar")))
      (spit (str dir "/extension.edn") "{:name \"ld\" :entry ld.main :loader [:sci :jolt]}")
      (is (= "target/test-pack-loader.jar"
             (build/pack-extension! dir "target/test-pack-loader.jar")))
      (fs/delete-tree dir)
      (fs/delete-if-exists "target/test-pack-loader.jar"))))

(deftest ^:bb-only pack-extension-roundtrip-loads
  (testing "packed jar of the clojure extension loads (fast: reused closure)"
    (let [out "target/test-pack-roundtrip.jar"]
      (fs/delete-if-exists out)
      (build/pack-extension! "extensions/clojure/src" out)
      (try
        (let [entries (with-open [zf (java.util.zip.ZipFile. (io/file out))]
                        (set (map (fn [e] (.getName ^java.util.zip.ZipEntry e))
                                  (enumeration-seq (.entries zf)))))]
          (is (contains? entries "extension.edn"))
          (is (contains? entries "kmet/extensions/clojure/core.clj"))
          (is (contains? entries "skills/clojure-edit/SKILL.md"))
          (is (not (contains? entries "META-INF/MANIFEST.MF")) "no META-INF"))
        (finally (fs/delete-if-exists out))))))
