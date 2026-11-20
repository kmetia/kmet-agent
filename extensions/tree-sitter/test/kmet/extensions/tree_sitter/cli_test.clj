(ns kmet.extensions.tree-sitter.cli-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.extensions.tree-sitter.cli :as cli]
            [kmet.extensions.tree-sitter.fetch :as fetch]
            [kmet.extensions.tree-sitter.paths :as paths]
            [kmet.extensions.tree-sitter.test-util :as tu]))

(deftest ^:bb-only process!-capture-test
  (testing "captures exit code and stdout"
    (let [res (cli/process! ["sh" "-c" "printf hi"] nil)]
      (is (= 0 (:exit res)))
      (is (= "hi" (str/trim (:out res))))))
  (testing "propagates non-zero exits without throwing"
    (let [res (cli/process! ["sh" "-c" "echo boom >&2; exit 3"] nil)]
      (is (= 3 (:exit res)))
      (is (str/includes? (str (:err res)) "boom")))))

(deftest ^:bb-only process!-timeout-test
  (let [res (cli/process! ["sleep" "30"] {:timeout-ms 150})]
    (is (= {:error :timeout} (select-keys res [:error])))))

(deftest ^:bb-only exec!-spawn-failure-test
  ;; base dir holds no binary -> resolution points at a nonexistent path
  (let [base (tu/temp-dir! "ts-spawn")]
    (with-redefs [cli/configured-binary (constantly nil)]
      (let [res (cli/exec! ["--version"] {:base base})]
        (is (= {:error :spawn-failure} (select-keys res [:error])))
        (is (string? (:reason res)))))))

(deftest ^:bb-only process!-extra-env-test
  (let [res (cli/process! ["sh" "-c" "printf $TS_PROBE_VAR"]
                          {:env {"TS_PROBE_VAR" "42"}})]
    (is (= "42" (str/trim (:out res))))))

(deftest ^:bb-only launcher-text-test
  (let [text (cli/launcher-text {:bin-name "tree-sitter" :linker "ld-linux-aarch64.so.1"})]
    (is (str/starts-with? text "#!/data/data/com.termux/files/usr/bin/sh"))
    (is (re-find #"glibc/lib/ld-linux-aarch64\.so\.1" text))
    (is (re-find #"\"\$DIR/tree-sitter\"" text))
    (is (re-find #"unset LD_PRELOAD" text))))

(deftest ^:bb-only emit-launcher!-test
  (let [base (tu/temp-dir! "ts-launcher")]
    (paths/ensure-dirs! base)
    (let [w (cli/emit-launcher! base)]
      (is (fs/exists? w))
      (is (str/includes? (slurp (str w)) "ld-linux"))
      (when-not (fs/windows?)
        (is (= "rwxr-xr-x" (fs/posix->str (fs/posix-file-permissions w))))))))

(deftest ^:bb-only resolve-invocation-test
  (let [base (tu/temp-dir! "ts-resolve")]
    (paths/ensure-dirs! base)
    (let [bin (paths/bin-path base)
          launcher (paths/launcher-path base)]
      (spit (str bin) "binary")
      (with-redefs [cli/configured-binary (constantly nil)]
        (testing "falls back to the binary when no launcher exists"
          (is (= bin (cli/resolve-invocation base))))
        (spit (str launcher) "#!/bin/sh\n")
        (testing "prefers the launcher once present"
          (is (= launcher (cli/resolve-invocation base))))))))

(defn- write-executable!
  "Write TEXT to NAME under DIR and mark it executable on posix hosts."
  [dir name text]
  (let [p (fs/path dir name)]
    (spit (str p) text)
    (when-not (fs/windows?)
      (fs/set-posix-file-permissions p "rwxr-xr-x"))
    p))

(defn- probe-system-binary!
  "Run ensure-binary! against a fake external CLI reporting VERSION and
   HELP; fetch/binary-release throws to prove no download is attempted."
  [version help]
  (let [base (tu/temp-dir! "ts-sys-probe")
        exe (write-executable! base "tree-sitter" "#!/bin/sh\n")]
    (with-redefs [cli/configured-binary (constantly (str exe))
                  fetch/binary-release (fn []
                                         (throw (ex-info "must not download" {})))
                  cli/process! (fn [cmd _]
                                 (case (second cmd)
                                   "--version" {:exit 0
                                                :out (str "tree-sitter " version)}
                                   "parse" {:exit 0 :out help}))]
      (cli/ensure-binary! nil))))

(deftest ^:bb-only system-binary-override-test
  (testing "a valid external CLI wins and skips the pin"
    (let [result (probe-system-binary! "0.27.1"
                                       "Usage: tree-sitter parse\n  --wasm\n")]
      (is (= "0.27.1" (:version result)))
      (is (true? (:system? result)))
      (is (fs/exists? (:path result)))))
  (testing "the tested lower bound is accepted"
    (is (= "0.26.11" (:version (probe-system-binary! "0.26.11" "--wasm")))))
  (testing "0.28.x with a commit suffix is accepted"
    (is (= "0.28.0"
           (:version (probe-system-binary! "0.28.0 (abc123)" "--wasm"))))))

(deftest ^:bb-only system-binary-rejection-test
  (testing "unsupported versions are rejected"
    (doseq [version ["0.26.10" "0.29.0"]]
      (is (thrown-with-msg? Exception #"supported versions"
                            (probe-system-binary! version "--wasm")))))
  (testing "a CLI built without WASM is rejected with the Nix hint"
    (is (thrown-with-msg? Exception #"without WASM support"
                          (probe-system-binary! "0.27.1"
                                                "Usage: tree-sitter parse\n"))))
  (testing "a failing parse --help cannot pass the WASM probe"
    (let [base (tu/temp-dir! "ts-sys-help")
          exe (write-executable! base "tree-sitter" "#!/bin/sh\n")]
      (with-redefs [cli/configured-binary (constantly (str exe))
                    fetch/binary-release (fn []
                                           (throw (ex-info "must not download" {})))
                    cli/process! (fn [cmd _]
                                   (case (second cmd)
                                     "--version" {:exit 0
                                                  :out "tree-sitter 0.27.1"}
                                     "parse" {:exit 2
                                              :out "--wasm"
                                              :err "help failed"}))]
        (is (thrown-with-msg? Exception #"could not verify WASM support"
                              (cli/ensure-binary! nil))))))
  (testing "a binary that does not report a tree-sitter version is rejected"
    (let [base (tu/temp-dir! "ts-sys-bad")
          exe (write-executable! base "tree-sitter" "#!/bin/sh\n")]
      (with-redefs [cli/configured-binary (constantly (str exe))
                    fetch/binary-release (fn []
                                           (throw (ex-info "must not download" {})))
                    cli/process! (fn [_ _] {:exit 0 :out "not a tree-sitter CLI"})]
        (is (thrown-with-msg? Exception #"not a working tree-sitter CLI"
                              (cli/ensure-binary! nil)))))))

(deftest ^:bb-only resolve-invocation-system-override-test
  (let [base (tu/temp-dir! "ts-sys-resolve")
        exe (write-executable! base "tree-sitter" "#!/bin/sh\n")]
    (with-redefs [cli/configured-binary (constantly (str exe))]
      (is (= (fs/absolutize exe) (cli/resolve-invocation base))))))

(deftest ^:bb-only smoke-version-diagnostic-test
  (let [base (tu/temp-dir! "ts-smoke")
        _ (paths/ensure-dirs! base)
        bin (paths/bin-path base)]
    (spit (str bin) "not a real binary")
    (with-redefs [cli/configured-binary (constantly nil)
                  cli/exec! (fn [_ _]
                              {:exit 127
                               :err "error while loading shared libraries: libc.so.6"})]
      (let [ex (try (cli/smoke-version! base "0.26.13")
                    nil
                    (catch Exception e e))]
        (is (some? ex))
        (is (str/includes? (ex-message ex) (str bin)))
        (is (str/includes? (ex-message ex) "exit 127"))
        (is (str/includes? (ex-message ex) "libc.so.6"))
        (is (str/includes? (ex-message ex) "KMET_TREE_SITTER_BIN"))))))

(deftest ^:bb-only smoke-version-accepts-pinned-output-test
  (let [base (tu/temp-dir! "ts-smoke-ok")
        _ (paths/ensure-dirs! base)
        bin (paths/bin-path base)]
    (spit (str bin) "binary")
    (with-redefs [cli/configured-binary (constantly nil)
                  cli/exec! (fn [_ _]
                              {:exit 0 :out "tree-sitter 0.26.13\n"})]
      (is (= {:exit 0 :out "tree-sitter 0.26.13\n"}
             (cli/smoke-version! base "0.26.13"))))))

(deftest ^:bb-only failed-install-memo-test
  (let [base-a (tu/temp-dir! "ts-memo-a")
        base-b (tu/temp-dir! "ts-memo-b")
        installs (atom 0)
        version (str "9.9.9-" (System/nanoTime))
        target (str "test-target-" (System/nanoTime))
        install (fn [base]
                  (swap! installs inc)
                  (spit (str (paths/bin-path base)) "x")
                  (paths/bin-path base))]
    (with-redefs [cli/configured-binary (constantly nil)
                  fetch/binary-release (constantly
                                        {:version version
                                         :targets {target {:binary-sha256 "unused"}}})
                  fetch/host-target (constantly target)
                  cli/install-binary! install
                  cli/smoke-version! (fn [base _]
                                       (if (= base base-a)
                                         (throw (ex-info "smoke boom" {:type ::test}))
                                         {:exit 0 :out "tree-sitter 9.9.9"}))]
      (dotimes [_ 2]
        (is (thrown-with-msg? Exception #"smoke boom"
                              (cli/ensure-binary! {:base base-a}))))
      (is (= 1 @installs) "the failed base is memoized")
      (let [result (cli/ensure-binary! {:base base-b})]
        (is (= (paths/bin-path base-b) (:path result)))
        (is (= 2 @installs) "a different base installs independently"))
      (is (thrown-with-msg? Exception #"smoke boom"
                            (cli/ensure-binary! {:base base-a})))
      (is (= 2 @installs) "the failed base stays memoized"))))
