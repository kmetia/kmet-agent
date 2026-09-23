(ns kmet.test-package-manager
  "Package CLI subcommand tests (pi: package-manager-cli.ts — the
   install/remove/uninstall/list/config subset with local dir/file sources):
   parsing, help, error messages, exit codes and output text."
  (:require [clojure.test :as t]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.package-manager :as pm]
            [kmet.config :as cfg]
            [kmet.test-utils :refer [slash]]))

(defn- tmp-dir []
  (str (fs/create-temp-dir {:dir (System/getenv "TMPDIR")})))

(defn- with-isolated-settings
  "Run F with the settings paths pointed at temp dirs."
  [f]
  (let [global-dir (tmp-dir)
        project-dir (str (fs/path (tmp-dir) ".kmet"))]
    (with-redefs [cfg/global-settings-path (fn [] (str (fs/path global-dir "settings.edn")))
                  cfg/get-agent-dir (fn [] global-dir)
                  cfg/project-settings-path (fn [] (str (fs/path project-dir "settings.edn")))
                  cfg/project-dir (fn [] project-dir)]
      (f {:global-dir global-dir :project-dir project-dir}))))

(defn- run
  "Run a package command capturing stdout/stderr; returns
   {:exit n :out str :err str}."
  [& args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err]
      (let [exit (pm/run-package-command (vec args))]
        {:exit exit :out (str out) :err (str err)}))))

;; ─── Command recognition ───────────────────────────────────────────────────

(t/deftest test-package-command?
  (t/is (pm/package-command? ["install" "x"]))
  (t/is (pm/package-command? ["remove" "x"]))
  (t/is (pm/package-command? ["uninstall" "x"]))
  (t/is (pm/package-command? ["list"]))
  (t/is (pm/package-command? ["config"]))
  (t/is (not (pm/package-command? ["--print" "hi"])))
  (t/is (not (pm/package-command? ["resume"]))))

;; ─── Help ──────────────────────────────────────────────────────────────────

(t/deftest test-help-exits-zero
  (t/is (= 0 (:exit (run "install" "--help"))))
  (t/is (= 0 (:exit (run "remove" "-h"))))
  (t/is (= 0 (:exit (run "list" "-h"))))
  (t/is (= 0 (:exit (run "config" "-h"))))
  (t/is (str/includes? (:out (run "install" "--help")) "kmet install <source>"))
  (t/is (str/includes? (:out (run "remove" "-h")) "kmet uninstall"))
  (t/is (str/includes? (:out (run "config" "-h")) "~/.kmet/agent/settings.edn")))

;; ─── Parse errors (pi exit-code-1 conventions) ────────────────────────────

(t/deftest test-parse-errors
  (let [r (run "install" "x" "--bogus")]
    (t/is (= 1 (:exit r)))
    (t/is (str/includes? (:err r) "Unknown option --bogus for \"install\".")))
  (let [r (run "remove" "a" "b")]
    (t/is (= 1 (:exit r)))
    (t/is (str/includes? (:err r) "Unexpected argument b.")))
  (let [r (run "install")]
    (t/is (= 1 (:exit r)))
    (t/is (str/includes? (:err r) "Missing install source.")))
  (let [r (run "remove")]
    (t/is (= 1 (:exit r)))
    (t/is (str/includes? (:err r) "Missing remove source.")))
  (t/testing "list ignores a stray positional (pi parity — no missing-source
             check for list, the source is simply unused)"
    (let [r (run "list" "x")]
      (t/is (= 0 (:exit r)))))
  (t/testing "-l is invalid for list (pi parity)"
    (let [r (run "list" "-l")]
      (t/is (= 1 (:exit r)))
      (t/is (str/includes? (:err r) "Unknown option -l")))))

;; ─── Install ───────────────────────────────────────────────────────────────

(t/deftest test-install-missing-path
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [r (run "install" (str global-dir "/nope"))]
        (t/is (= 1 (:exit r)))
        (t/is (str/includes? (:err r) "Error: Path does not exist: "))
        (t/is (not (fs/exists? (cfg/global-settings-path))))))))

(t/deftest test-install-remote-source-rejected
  (with-isolated-settings
    (fn [_]
      (let [r (run "install" "npm:@foo/bar")]
        (t/is (= 1 (:exit r)))
        (t/is (str/includes? (:err r) "Unsupported package source"))
        (t/is (str/includes? (:err r) "local directories and files only"))))))

(t/deftest test-install-directory
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg (str global-dir "/my-ext")
            _ (fs/create-dirs pkg)
            r (run "install" pkg)]
        (t/is (= 0 (:exit r)))
        (t/is (str/includes? (:out r) (str "Installed " pkg)))
        (let [settings (edn/read-string (slurp (cfg/global-settings-path)))]
          (t/is (= ["my-ext"] (:packages settings))))))))

(t/deftest test-install-file-project-local
  (with-isolated-settings
    (fn [{:keys [project-dir]}]
      (let [pkg (str (fs/normalize (fs/path project-dir ".." "pkg")))
            _ (fs/create-dirs pkg)
            f (str pkg "/ext.clj")]
        (spit f "(ns ext)\n")
        (let [r (run "install" f "-l")]
          (t/is (= 0 (:exit r)))
          (let [settings (edn/read-string (slurp (cfg/project-settings-path)))]
            (t/is (= ["../pkg/ext.clj"] (:packages settings))))
         ;; the -a/--no-approve flags parse as no-ops
          (let [r2 (run "install" f "--local" "--no-approve")]
            (t/is (= 0 (:exit r2)))))))))

(t/deftest test-install-existing-matches-and-stays
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg (str global-dir "/pkg")
            _ (fs/create-dirs pkg)]
        (t/is (= 0 (:exit (run "install" pkg))))
        (let [r (run "install" (str pkg "/sub/../"))]
          (t/is (= 0 (:exit r)))
          (t/is (= 1 (count (:packages (edn/read-string (slurp (cfg/global-settings-path))))))))))))

(t/deftest test-install-relative-source-resolves-against-cwd
  ;; pi install resolves the source against the cwd; the settings base dir
  ;; only defines what a stored relative source means
  (with-isolated-settings
    (fn [_]
      (let [work (tmp-dir)
            _ (fs/create-dirs (str work "/pkg"))
            _ (spit (str work "/pkg/ext.clj") "(ns x)\n")
            orig (System/getProperty "user.dir")]
        (try
          (System/setProperty "user.dir" work)
          (let [r (run "install" "./pkg")]
            (t/is (= 0 (:exit r)))
            (t/is (str/includes? (:out r) "Installed ./pkg"))
            (t/is (= 1 (count (:packages (edn/read-string
                                          (slurp (cfg/global-settings-path))))))))
          (finally
            (System/setProperty "user.dir" orig)))))))

;; ─── Remove ────────────────────────────────────────────────────────────────

(t/deftest test-remove
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg (str global-dir "/pkg")
            _ (fs/create-dirs pkg)]
        (run "install" pkg)
        (let [r (run "remove" pkg)]
          (t/is (= 0 (:exit r)))
          (t/is (str/includes? (:out r) (str "Removed " pkg)))
          (t/is (empty? (:packages (edn/read-string (slurp (cfg/global-settings-path)))))))
        (t/testing "removing something not configured"
          (let [r (run "remove" pkg)]
            (t/is (= 1 (:exit r)))
            (t/is (str/includes? (:err r) (str "No matching package found for " pkg)))))))))

(t/deftest test-uninstall-alias
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg (str global-dir "/pkg")
            _ (fs/create-dirs pkg)]
        (run "install" pkg)
        (let [r (run "uninstall" pkg)]
          (t/is (= 0 (:exit r)))
          (t/is (str/includes? (:out r) (str "Removed " pkg))))))))

;; ─── List ──────────────────────────────────────────────────────────────────

(t/deftest test-list-empty
  (with-isolated-settings
    (fn [_]
      (let [r (run "list")]
        (t/is (= 0 (:exit r)))
        (t/is (str/includes? (:out r) "No packages installed."))))))

(t/deftest test-list-user-and-project
  (with-isolated-settings
    (fn [{:keys [global-dir project-dir]}]
      (let [pkg (str global-dir "/pkg")
            _ (fs/create-dirs pkg)
            proj-pkg (str (fs/normalize (fs/path project-dir ".." "proj")))
            _ (fs/create-dirs proj-pkg)]
        (run "install" pkg)
        (run "install" proj-pkg "-l")
        (let [r (run "list")
              out (:out r)]
          (t/is (= 0 (:exit r)))
          (t/is (str/includes? out "User packages:"))
          (t/is (str/includes? out "Project packages:"))
          (t/is (str/includes? out (str "  " (fs/file-name pkg))))
          (t/is (str/includes? (slash out) (str "    " (slash pkg))))
          (t/is (str/includes? out "  ../proj")))))))
