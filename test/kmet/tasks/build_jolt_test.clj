(ns kmet.tasks.build-jolt-test
  ;; The jolt packager's pure surface (kmet.tasks.build-jolt, the jolt branch of the
  ;; `dist` task): platform/naming rules and the CLI parser. The compile itself is
  ;; jolt's CLI in a subprocess and is not unit-tested here; only the host's
  ;; own artifact can smoke-test, which the packager does as part of the build.
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.build :as build]
            [kmet.tasks.build-jolt :as jbuild]))

(deftest platform-for-is-the-shared-host-vocabulary
  ;; kmet.tasks.build/platform-for: the babashka packager stamps the same
  ;; platform string on its artifacts, so one dist/ lines both hosts up
  (is (= "linux-amd64" (build/platform-for "Linux" "amd64")))
  (is (= "linux-amd64" (build/platform-for "Linux" "x86_64")))
  (is (= "linux-aarch64" (build/platform-for "Linux" "aarch64")))
  (is (= "linux-aarch64" (build/platform-for "Linux" "arm64")))
  (is (= "macos-aarch64" (build/platform-for "Mac OS X" "arm64")))
  (is (= "macos-amd64" (build/platform-for "Darwin" "x86_64")))
  (is (= "windows-amd64" (build/platform-for "Windows 11" "amd64")))
  (testing "no -static variants: a jolt binary carries its own runtime"
    (is (= "linux-amd64" (build/platform-for "linux" "amd64"))))
  (testing "unsupported pairs have no platform"
    (is (nil? (build/platform-for "SunOS" "sparc")))
    (is (nil? (build/platform-for "Linux" "riscv64")))))

(deftest target-platform-maps-chez-machines
  (testing "the host when no cross target is given"
    (is (= (jbuild/host-platform) (jbuild/target-platform nil)))
    (is (= (jbuild/host-platform) (jbuild/target-platform ""))))
  (testing "Chez machine strings"
    (is (= "linux-amd64" (jbuild/target-platform "ta6le")))
    (is (= "linux-aarch64" (jbuild/target-platform "tarm64le")))
    (is (= "macos-amd64" (jbuild/target-platform "ta6osx")))
    (is (= "macos-aarch64" (jbuild/target-platform "tarm64osx")))
    (is (= "windows-amd64" (jbuild/target-platform "ta6nt"))))
  (testing "an unmapped machine keeps its own name"
    (is (= "tb3le" (jbuild/target-platform "tb3le")))))

(deftest windows-platform-drives-the-exe-suffix
  (is (true? (jbuild/windows-platform? "windows-amd64")))
  (is (true? (jbuild/windows-platform? "windows-aarch64")))
  (is (false? (jbuild/windows-platform? "linux-amd64"))))

(deftest static-native-specs-must-be-static
  (let [validate #'jbuild/validate-static-build!
        crypto {:name "crypto"
                :static {:linux {:archive "linux/libcrypto.a"}
                         :darwin {:archive "darwin/libcrypto.a"}
                         :windows {:archive "windows/libcrypto.a"}}}
        process {:name "libc" :process true}]
    (is (nil? (validate "windows-amd64" [crypto process])))
    (is (nil? (validate "linux-amd64" [crypto process])))
    (is (nil? (validate "macos-aarch64" [crypto process])))
    (testing "a file-backed native without this platform's static link fails closed"
      (is (thrown-with-msg? Exception #":linux :static link: ssl"
                            (validate "linux-amd64"
                                      [crypto {:name "ssl" :linux ["libssl.so"]}]))))
    (testing "process natives need no archive"
      (is (nil? (validate "windows-amd64" [process]))))))

(deftest native-link-mode-defaults-per-platform
  (let [mode #'jbuild/native-link-mode]
    (is (= :static (mode "windows-amd64" nil)))
    (is (= :dynamic (mode "linux-amd64" nil)))
    (is (= :dynamic (mode "macos-aarch64" nil)))
    (testing "an explicit choice overrides the platform default"
      (is (= :dynamic (mode "windows-amd64" :dynamic)))
      (is (= :static (mode "linux-amd64" :static))))))

(deftest static-cross-build-detection-is-host-relative
  (let [cross? #'jbuild/cross-static-build?]
    (with-redefs [jbuild/host-platform (constantly "windows-amd64")]
      (is (false? (cross? "windows-amd64" "ta6nt")))
      (is (true? (cross? "linux-amd64" "ta6le")))
      (is (not (cross? "windows-amd64" nil))))))

(deftest deps-declare-static-archives-and-runtime-candidates
  (let [config (edn/read-string (slurp "deps.edn"))
        by-name (into {} (map (juxt :name identity) (:jolt/native config)))]
    (is (= "0.8.11" (:jolt/min-version config)))
    (is (= ["crypto" "ssl"] (mapv :name (:jolt/native config))))
    (doseq [[lib-name filename] [["crypto" "libcrypto.a"] ["ssl" "libssl.a"]]
            [key prefix] [[:linux "target/jolt-native/linux/"]
                          [:darwin "target/jolt-native/darwin/"]
                          [:windows "target/jolt-native/windows/"]]]
      (is (= {:archive (str prefix filename)}
             (get-in by-name [lib-name :static key]))
          (name key))
      (is (seq (get-in by-name [lib-name key])) lib-name))))

(deftest windows-compiler-wrapper-closes-openssl-link-cycles
  (let [command (@#'jbuild/compiler-wrapper-command
                 "C:\\MinGW\\bin\\cc.exe"
                 "C:\\kmet\\target\\jolt-native\\windows\\libcrypto.a")]
    (is (str/starts-with? command "#!/bin/sh\n"))
    (is (str/includes? command "*-shared*libssl.a*)"))
    (is (str/includes? command
                       "-Wl,--whole-archive \"C:/kmet/target/jolt-native/windows/libcrypto.a\""))
    (is (str/includes? command "exec \"C:/MinGW/bin/cc.exe\" \"$@\""))
    (is (str/includes? command "-L\"C:/kmet/target/jolt-native/windows\""))
    (is (str/includes? command "-lws2_32 -lcrypt32 -lz"))))

(deftest generated-scheme-proves-natives-were-static
  (let [dir "target/test-jolt-static-natives"
        bin (str dir "/kmet.exe")
        flat (str dir "/kmet.exe.build/flat.ss")
        verify #'jbuild/verify-static-native-build!
        natives [{:name "crypto" :static {:windows {:archive "crypto.a"}}}
                 {:name "ssl" :static {:windows {:archive "ssl.a"}}}
                 {:name "libc" :process true}]]
    (fs/delete-tree dir)
    (fs/create-dirs (fs/parent flat))
    (try
      (spit flat (str "(jolt-build-load-native '() #f #t)\n"
                      "(jolt-build-load-native '() #f #t)\n"
                      "(jolt-build-load-native '() #f #t)\n"))
      (is (true? (verify bin natives)))
      (testing "a dynamic fallback is detected even when the binary exists"
        (spit flat (str "(jolt-build-load-native (list \"libcrypto-3-x64.dll\") #f #f)\n"
                        "(jolt-build-load-native '() #f #t)\n"
                        "(jolt-build-load-native '() #f #t)\n"))
        (is (thrown-with-msg? Exception #"did not statically link every native"
                              (verify bin natives))))
      (finally
        (fs/delete-tree dir)))))

(deftest artifact-base-mirrors-the-babashka-naming
  (is (= "kmet-1.2.3-jolt0.8.6-linux-amd64"
         (jbuild/artifact-base "1.2.3" "0.8.6" "linux-amd64" {})))
  (is (= "kmet-20260911-abc1234-jolt0.8.6-86-g234f460b-windows-amd64"
         (jbuild/artifact-base "20260911-abc1234" "0.8.6-86-g234f460b"
                               "windows-amd64" {})))
  (testing "a dev build cannot be mistaken for a release artifact"
    (is (= "kmet-1.2.3-jolt0.8.6-linux-amd64-dev"
           (jbuild/artifact-base "1.2.3" "0.8.6" "linux-amd64" {:dev? true}))))
  (testing "a --test build is named kmet-test-*"
    (is (= "kmet-test-1.2.3-jolt0.8.6-linux-amd64"
           (jbuild/artifact-base "1.2.3" "0.8.6" "linux-amd64" {:test? true})))
    (is (= "kmet-test-1.2.3-jolt0.8.6-linux-amd64-dev"
           (jbuild/artifact-base "1.2.3" "0.8.6" "linux-amd64"
                                 {:test? true :dev? true}))))
  (testing "no jolt version (babashka, where the var is absent) still names"
    (is (= "kmet-1.2.3-joltdev-linux-amd64"
           (jbuild/artifact-base "1.2.3" nil "linux-amd64" {})))))

(deftest parse-args-defaults-to-a-release-host-build
  (is (= {:mode "release" :flags [] :native-link nil :boot nil :target nil
          :target-pack nil :out nil :jolt nil :force? false :no-smoke? true
          :test? false :help? false}
         (jbuild/parse-args []))))

(deftest parse-args-reads-modes-and-passthrough-flags
  (is (= "dev" (:mode (jbuild/parse-args ["--dev"]))))
  (is (= "optimized" (:mode (jbuild/parse-args ["--opt"]))))
  (is (= ["--closed-world"] (:flags
                             (jbuild/parse-args ["--closed-world"]))))
  (testing "native linking is an explicit pair, not a passthrough flag"
    (is (= :static (:native-link (jbuild/parse-args ["--static"]))))
    (is (= :dynamic (:native-link (jbuild/parse-args ["--dynamic"]))))
    (is (= :static (:native-link (jbuild/parse-args ["--static" "--static"])))))
  (is (= ["--tree-shake"] (:flags (jbuild/parse-args ["--tree-shake"]))))
  (is (= "small" (:boot (jbuild/parse-args ["--boot" "small"]))))
  (testing "an option's value is not read as a positional argument"
    (is (= "small" (:boot (jbuild/parse-args ["--boot" "small" "--no-smoke"])))))
  (is (true? (:force? (jbuild/parse-args ["--force"]))))
  (testing "the smoke test is opt-in"
    (is (false? (:no-smoke? (jbuild/parse-args ["--smoke"]))))
    (is (true? (:no-smoke? (jbuild/parse-args ["--no-smoke"])))))
  (is (true? (:help? (jbuild/parse-args ["-h"]))))
  (is (= "/tmp/kmet" (:out (jbuild/parse-args ["-o" "/tmp/kmet"]))))
  (is (= "/tmp/kmet" (:out (jbuild/parse-args ["--out" "/tmp/kmet"]))))
  (is (= "/opt/jolt" (:jolt (jbuild/parse-args ["--jolt" "/opt/jolt"]))))
  (testing "--test builds the compiled test runner"
    (is (true? (:test? (jbuild/parse-args ["--test"]))))
    (is (true? (:test? (jbuild/parse-args ["--test" "--dev"]))))))

(deftest parse-args-reads-cross-builds
  (let [opts (jbuild/parse-args ["--target" "tarm64le" "--target-pack" "/tmp/pack"])]
    (is (= "tarm64le" (:target opts)))
    (is (= "/tmp/pack" (:target-pack opts)))))

(deftest parse-args-rejects-bad-input
  (is (thrown-with-msg? Exception #"unknown option"
                        (jbuild/parse-args ["--optt"])))
  (testing "the native-link modes are mutually exclusive"
    (is (thrown-with-msg? Exception #"either --static or --dynamic"
                          (jbuild/parse-args ["--static" "--dynamic"])))
    (is (thrown-with-msg? Exception #"either --static or --dynamic"
                          (jbuild/parse-args ["--dynamic" "--static"]))))
  (testing "no positional targets: a jolt cross build needs a pack, not a download"
    (is (thrown-with-msg? Exception #"unexpected argument"
                          (jbuild/parse-args ["linux-amd64"]))))
  (is (thrown-with-msg? Exception #"--boot needs"
                        (jbuild/parse-args ["--boot" "medium"])))
  (is (thrown-with-msg? Exception #"--boot needs"
                        (jbuild/parse-args ["--boot"])))
  (is (thrown-with-msg? Exception #"--target needs"
                        (jbuild/parse-args ["--target"])))
  (is (thrown-with-msg? Exception #"--target-pack needs"
                        (jbuild/parse-args ["--target-pack"]))))

(deftest default-artifact-appends-exe-only-on-windows
  (let [artifact #'jbuild/default-artifact]
    (is (= "dist/kmet-1.2.3-jolt0.8.6-linux-amd64"
           (str (artifact "1.2.3" "0.8.6" "linux-amd64" "release"))))
    (is (= "dist/kmet-1.2.3-jolt0.8.6-windows-amd64.exe"
           (str (artifact "1.2.3" "0.8.6" "windows-amd64" "release"))))
    (testing "the dev tag sits before the suffix"
      (is (= "dist/kmet-1.2.3-jolt0.8.6-linux-amd64-dev"
             (str (artifact "1.2.3" "0.8.6" "linux-amd64" "dev"))))
      (is (= "dist/kmet-1.2.3-jolt0.8.6-windows-amd64-dev.exe"
             (str (artifact "1.2.3" "0.8.6" "windows-amd64" "dev")))))
    (testing "--test names the test artifact"
      (is (= "dist/kmet-test-1.2.3-jolt0.8.6-linux-amd64"
             (str (artifact "1.2.3" "0.8.6" "linux-amd64" "release" {:test? true}))))
      (is (= "dist/kmet-test-1.2.3-jolt0.8.6-windows-amd64-dev.exe"
             (str (artifact "1.2.3" "0.8.6" "windows-amd64" "dev" {:test? true})))))))

(deftest scratch-bin-separates-test-builds
  (is (= "kmet" (str (fs/file-name (@#'jbuild/scratch-bin "linux-amd64" "dev")))))
  (is (= "kmet-test"
         (str (fs/file-name (@#'jbuild/scratch-bin "linux-amd64" "dev" {:test? true})))))
  (testing "a test build gets its own incremental dir"
    (is (not= (str (@#'jbuild/scratch-bin "linux-amd64" "dev"))
              (str (@#'jbuild/scratch-bin "linux-amd64" "dev" {:test? true}))))))

(deftest build-argv-pins-the-entry-and-output
  (let [argv #'jbuild/build-argv]
    (is (= ["build" "-m" "kmet.core" "-o" "/tmp/out/kmet"]
           (argv {:mode "release" :flags [] :out "/tmp/out/kmet"})))
    (testing "mode, boot and passthrough flags follow the output"
      (is (= ["build" "-m" "kmet.core" "-o" "/o" "--dev" "--closed-world"]
             (argv {:mode "dev" :flags ["--closed-world"] :out "/o"})))
      (is (= ["build" "-m" "kmet.core" "-o" "/o" "--opt" "--boot" "small"]
             (argv {:mode "optimized" :boot "small" :flags [] :out "/o"}))))
    (testing "native link mode is explicit only when dynamic"
      (is (= ["build" "-m" "kmet.core" "-o" "/o" "--dynamic"]
             (argv {:mode "release" :flags [] :native-link :dynamic :out "/o"})))
      (is (= ["build" "-m" "kmet.core" "-o" "/o"]
             (argv {:mode "release" :flags [] :native-link :static :out "/o"}))))
    (testing "cross builds carry the target and its pack"
      (is (= ["build" "-m" "kmet.core" "-o" "/o" "--target" "tarm64le" "--target-pack" "/p"]
             (argv {:mode "release" :flags [] :out "/o"
                    :target "tarm64le" :target-pack "/p"}))))
    (testing "--test selects the generated test entry and its source roots"
      (is (= ["-A:kmet-test" "build" "-m" "kmet.tasks.test-main" "-o" "/o" "--dev"]
             (argv {:mode "dev" :flags [] :out "/o" :test? true}))))
    (testing "a plain app build never sees the test roots"
      (is (not-any? #{"-A:kmet-test" "kmet.tasks.test-main"}
                    (argv {:mode "release" :flags [] :out "/o"}))))))

(deftest binary-interpreter-probe-drives-the-launcher
  ;; the Termux launcher is only for glibc-linked artifacts; a jolt linked
  ;; with the bionic cc runs directly and must not get one (a stale launcher
  ;; from an earlier glibc build of the same path is removed)
  (let [dir "target/test-jolt-launcher"
        glibc (str dir "/glibc.bin")
        bionic (str dir "/bionic.bin")
        launcher (str dir "/bionic.bin.sh")]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    ;; a needle straddling the 64 KiB chunk boundary is the interesting case
    (spit bionic (str (apply str (repeat 65530 "x")) "linker64" (apply str (repeat 100 "y"))))
    (spit glibc (str (apply str (repeat 65530 "x")) "/lib/ld-linux-aarch64.so.1"))
    (try
      (is (@#'jbuild/binary-contains? glibc "ld-linux"))
      (is (not (@#'jbuild/binary-contains? bionic "ld-linux")))
      (is (@#'jbuild/binary-contains? bionic "linker64"))
      (is (@#'jbuild/glibc-linked? glibc))
      (is (not (@#'jbuild/glibc-linked? bionic)))
      (spit launcher "#!/bin/sh\n")
      (with-redefs [build/termux? (constantly true)
                    jbuild/host-platform (constantly "linux-aarch64")
                    fs/set-posix-file-permissions (fn [_ _])]
        (is (nil? (@#'jbuild/write-launcher! bionic "linux-aarch64"))
            "bionic artifact gets no launcher")
        (is (not (fs/exists? launcher)) "...and the stale one is removed")
        (is (= (str (fs/path dir "glibc.bin.sh"))
               (str (@#'jbuild/write-launcher! glibc "linux-aarch64")))
            "a glibc artifact gets the launcher"))
      (finally (fs/delete-tree dir)))))

(deftest smoke-agent-dir-enables-a-native-bundled-extension
  (let [dir (str "target/test-jolt-smoke-agent-" (System/currentTimeMillis))]
    (fs/delete-tree dir)
    (try
      (let [agent-dir (@#'jbuild/prepare-smoke-agent-dir dir)
            settings (slurp (str (fs/path agent-dir "settings.edn")))]
        (is (= (str/replace dir "\\" "/")
               (str/replace (str (fs/parent agent-dir)) "\\" "/")))
        (is (str/includes? settings ":bundled-extensions")
            "the app smoke runs with an extension enabled")
        (is (str/includes? settings "\"clojure\"")
            "a bundled directory extension exercises the native embedded root")
        (is (str/includes? settings "\"deepseek-peak\"")
            "a bundled single file exercises the exact native source mapping"))
      (finally
        (fs/delete-tree dir)))))
