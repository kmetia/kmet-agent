(ns kmet.test-package-manager
  "Package CLI subcommand tests (pi: package-manager-cli.ts — the
   install/remove/uninstall/list/config subset with local dir/file sources):
   parsing, help, error messages, exit codes and output text."
  (:require [clojure.test :as t]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.git-packages :as gitp]
            [kmet.app.packages-cli :as pm]
            [kmet.ui.tui.config-command :as config-command]
            [kmet.app.config :as cfg]
            [kmet.app.packages :as pkgs]
            [kmet.ui.tui.resource-config :as resource-config]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.libs.theme :as theme]
            [kmet.test-utils :refer [slash]]
            [kmet.test-tmp :as test-tmp]))

(defn- tmp-dir []
  (test-tmp/temp-dir! "test-pkg-"))

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
  (t/is (pm/package-command? ["update"]))
  (t/is (pm/package-command? ["list"]))
  (t/is (pm/package-command? ["config"]))
  (t/is (not (pm/package-command? ["--print" "hi"])))
  (t/is (not (pm/package-command? ["resume"]))))

;; ─── Help ──────────────────────────────────────────────────────────────────

(t/deftest test-help-exits-zero
  (t/is (= 0 (:exit (run "install" "--help"))))
  (t/is (= 0 (:exit (run "remove" "-h"))))
  (t/is (= 0 (:exit (run "update" "-h"))))
  (t/is (= 0 (:exit (run "list" "-h"))))
  (t/is (= 0 (:exit (run "config" "-h"))))
  (t/is (str/includes? (:out (run "install" "--help")) "kmet install <source>"))
  (t/is (str/includes? (:out (run "install" "--help")) "git:github.com/user/repo"))
  (t/is (str/includes? (:out (run "remove" "-h")) "kmet uninstall"))
  (t/is (str/includes? (:out (run "update" "--help")) "kmet update [source]"))
  (t/is (str/includes? (:out (run "update" "--help")) "kmet cannot self-update"))
  (t/is (str/includes? (:out (run "config" "-h")) "~/.kmet/agent/patch.edn"))
  (t/is (str/includes? (:out (run "config" "-h")) "scope's settings.edn")))

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
        (t/is (str/includes? (:err r) "npm installs are not ported"))))))

(t/deftest test-install-git
  (with-isolated-settings
    (fn [{:keys [global-dir project-dir]}]
      (let [calls (atom [])]
        (with-redefs [gitp/install! (fn [source root]
                                      (swap! calls conj [source root])
                                      (fs/create-dirs (gitp/package-dir source root))
                                      (str root))]
          (t/testing "a global install clones under the agent git/ root"
            (let [r (run "install" "git:github.com/user/repo@v1")]
              (t/is (= 0 (:exit r)))
              (t/is (str/includes? (:out r) "Installing git:github.com/user/repo@v1..."))
              (t/is (str/includes? (:out r) "Installed git:github.com/user/repo@v1"))
              (let [[source root] (first @calls)]
                (t/is (= "github.com" (:host source)))
                (t/is (= "v1" (:ref source)))
                (t/is (= (str (fs/path global-dir "git")) root)))
              (t/is (= ["git:github.com/user/repo@v1"]
                       (:packages (edn/read-string (slurp (cfg/global-settings-path))))))))
          (t/testing "a local install targets the project git/ root"
            (let [r (run "install" "git:github.com/user/repo" "-l")]
              (t/is (= 0 (:exit r)))
              (t/is (= (str (fs/path project-dir "git")) (second (last @calls))))
              (t/is (str/includes? (slurp (cfg/project-settings-path))
                                   "git:github.com/user/repo")))))))))

(t/deftest test-install-git-failure-writes-nothing
  (with-isolated-settings
    (fn [_]
      (with-redefs [gitp/install! (fn [_ _]
                                    (throw (ex-info "git clone failed"
                                                    {:type :git-command-failed})))]
        (let [r (run "install" "git:github.com/user/repo")]
          (t/is (= 1 (:exit r)))
          (t/is (str/includes? (:err r) "Error: git clone failed"))
          (t/is (not (fs/exists? (cfg/global-settings-path)))))))))

(t/deftest test-install-git-subdir
  (with-isolated-settings
    (fn [_]
      (t/testing "a subdirectory the repository does not have fails without persisting"
        (let [checkout (atom nil)]
          (with-redefs [gitp/install! (fn [source root]
                                        (reset! checkout (gitp/install-path root source))
                                        (fs/create-dirs @checkout)
                                        (str root))]
            (let [r (run "install" "git:github.com/user/repo//packages/nope")]
              (t/is (= 1 (:exit r)))
              (t/is (str/includes? (:err r) "No directory \"packages/nope\""))
              (t/is (str/includes? (:err r) "directories"))
              (t/is (not (fs/exists? (cfg/global-settings-path))))
              (t/is (not (fs/exists? @checkout))
                    "the freshly cloned, unshared checkout is cleaned up")))))
      (t/testing "an existing subdirectory records the source verbatim"
        (with-redefs [gitp/install! (fn [source root]
                                      (fs/create-dirs (gitp/package-dir source root))
                                      (str root))]
          (let [r (run "install" "git:github.com/user/repo//packages/a")]
            (t/is (= 0 (:exit r)))
            (t/is (= ["git:github.com/user/repo//packages/a"]
                     (:packages (edn/read-string (slurp (cfg/global-settings-path))))))))))))

(t/deftest test-install-git-subdir-keeps-existing-checkout
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (t/testing "a checkout that already existed is kept when the subdirectory is wrong"
        (let [checkout (str (fs/path global-dir "git" "github.com" "user" "repo"))]
          (fs/create-dirs checkout)
          (with-redefs [gitp/install! (fn [_ root] (str root))]
            (let [r (run "install" "git:github.com/user/repo//packages/nope")]
              (t/is (= 1 (:exit r)))
              (t/is (fs/exists? checkout))))))
      (t/testing "a checkout a configured sibling uses is kept even when freshly cloned"
        (cfg/save-setting! [:packages] ["git:github.com/user/repo//packages/a"])
        (let [checkout (str (fs/path global-dir "git" "github.com" "user" "repo"))]
          (fs/delete-tree checkout)
          (with-redefs [gitp/install! (fn [_ root]
                                        (fs/create-dirs checkout)
                                        (str root))]
            (let [r (run "install" "git:github.com/user/repo//packages/nope")]
              (t/is (= 1 (:exit r)))
              (t/is (fs/exists? checkout) "the sibling's checkout stays")
              (t/is (= ["git:github.com/user/repo//packages/a"]
                       (:packages (edn/read-string (slurp (cfg/global-settings-path)))))))))))))

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

;; ─── Git update (kmet: git packages only) ─────────────────────────────────

(t/deftest test-remove-git
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (with-redefs [gitp/install! (fn [source root]
                                    (fs/create-dirs (gitp/package-dir source root))
                                    (str root))]
        (run "install" "git:github.com/user/repo"))
      (let [removed (atom nil)]
        (with-redefs [gitp/remove! (fn [source root] (reset! removed [source root]))]
          (let [r (run "remove" "git:github.com/user/repo")]
            (t/is (= 0 (:exit r)))
            (t/is (str/includes? (:out r) "Removed git:github.com/user/repo"))
            (t/is (= (str (fs/path global-dir "git")) (second @removed)))
            (t/is (empty? (:packages (edn/read-string
                                      (slurp (cfg/global-settings-path))))))))))))

(t/deftest test-remove-git-shared-checkout
  (with-isolated-settings
    (fn [_]
      (cfg/save-setting! [:packages] ["git:github.com/user/repo//packages/a"
                                      "git:github.com/user/repo//packages/b"])
      (let [removed (atom [])]
        (with-redefs [gitp/remove! (fn [source _root]
                                     (swap! removed conj (gitp/repo-identity source)))]
          (t/testing "removing one subdirectory keeps the shared checkout"
            (let [r (run "remove" "git:github.com/user/repo//packages/a")]
              (t/is (= 0 (:exit r)))
              (t/is (empty? @removed))
              (t/is (= 1 (count (:packages (edn/read-string
                                            (slurp (cfg/global-settings-path)))))))))
          (t/testing "removing the last user of the checkout deletes it"
            (let [r (run "remove" "git:github.com/user/repo//packages/b")]
              (t/is (= 0 (:exit r)))
              (t/is (= ["github.com/user/repo"] @removed))
              (t/is (empty? (:packages (edn/read-string
                                        (slurp (cfg/global-settings-path)))))))))))))

(t/deftest test-update-command
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (t/testing "nothing configured"
        (let [r (run "update")]
          (t/is (= 0 (:exit r)))
          (t/is (str/includes? (:out r) "No git packages installed."))))
      (cfg/save-setting! [:packages] ["git:github.com/user/repo" (str global-dir "/local-pkg")])
      (let [calls (atom [])]
        (with-redefs [gitp/update! (fn [source root] (swap! calls conj [source root]))]
          (t/testing "a bare update updates every git package"
            (let [r (run "update")]
              (t/is (= 0 (:exit r)))
              (t/is (= 1 (count @calls)))
              (t/is (= (str (fs/path global-dir "git")) (second (first @calls))))
              (t/is (str/includes? (:out r) "Updated packages"))))
          (t/testing "--extensions is the explicit form of the same"
            (let [r (run "update" "--extensions")]
              (t/is (= 0 (:exit r)))
              (t/is (= 2 (count @calls)))))
          (t/testing "a source updates the matching git package by identity"
            (let [r (run "update" "git:https://github.com/user/repo@v2")]
              (t/is (= 0 (:exit r)))
              (t/is (= 3 (count @calls)))
              (t/is (str/includes? (:out r) "Updated git:https://github.com/user/repo@v2"))))
          (t/testing "an unmatched source errors with the shorthand suggestion"
            (let [r (run "update" "github.com/user/repo")]
              (t/is (= 1 (:exit r)))
              (t/is (str/includes? (:err r) "No matching package found"))
              (t/is (str/includes? (:err r) "Did you mean git:github.com/user/repo?")))))))))

(t/deftest test-update-git-subdirectories-dedupe-checkouts
  (with-isolated-settings
    (fn [_]
      (cfg/save-setting! [:packages] ["git:github.com/user/repo//packages/a"
                                      "git:github.com/user/repo//packages/b"])
      (let [calls (atom [])]
        (with-redefs [gitp/update! (fn [source root]
                                     (swap! calls conj [(gitp/repo-identity source) root]))]
          (t/testing "a bare update fetches each shared checkout once"
            (let [r (run "update")]
              (t/is (= 0 (:exit r)))
              (t/is (= 1 (count @calls)))
              (t/is (str/includes? (:out r) "Updated packages"))))
          (t/testing "the repository URL matches every subdirectory package"
            (let [r (run "update" "git:github.com/user/repo")]
              (t/is (= 0 (:exit r)))
              (t/is (= 2 (count @calls)))))
          (t/testing "a subdirectory URL updates the same shared checkout"
            (let [r (run "update" "git:github.com/user/repo//packages/a")]
              (t/is (= 0 (:exit r)))
              (t/is (= 3 (count @calls))))))))))

(t/deftest test-update-local-and-npm
  (with-isolated-settings
    (fn [{:keys [global-dir]}]
      (let [pkg (str (fs/path global-dir "pkg"))]
        (fs/create-dirs pkg)
        (cfg/save-setting! [:packages] [pkg])
        (t/testing "a matched local source has nothing to update"
          (let [r (run "update" pkg)]
            (t/is (= 0 (:exit r)))
            (t/is (str/includes? (:out r) "Local packages load from disk")))))
      (t/testing "an unmatched local source errors (pi identity match)"
        (let [r (run "update" "./not-configured")]
          (t/is (= 1 (:exit r)))
          (t/is (str/includes? (:err r) "No matching package found"))))
      (t/testing "npm sources are not supported"
        (let [r (run "update" "npm:@foo/bar")]
          (t/is (= 1 (:exit r)))
          (t/is (str/includes? (:err r) "git packages only")))))))

(t/deftest test-update-unsupported-options
  (with-isolated-settings
    (fn [_]
      (doseq [[args text] [[["update" "--self"] "self-update"]
                           [["update" "--models"] "kmet --generate-models"]
                           [["update" "--all"] "self-update"]
                           [["update" "--force"] "self-update"]
                           [["update" "--extension" "git:github.com/user/repo"]
                            "kmet update <source>"]
                           [["update" "self"] "self-update"]
                           [["update" "pi"] "self-update"]]]
        (let [r (apply run args)]
          (t/is (= 1 (:exit r)) (pr-str args))
          (t/is (str/includes? (:err r) text) (pr-str args)))))))

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

(t/deftest config-disposes-its-screen-on-every-exit-path
  (t/testing "finding 5: run-config disposes the screen (the terminal-size
            watch cancel) on the normal path and when tui-start throws"
    (let [disposed (atom 0)
          size-ref (atom {:rows 24})
          screen (reify protocols/IComponent
                   (render [_ _] [])
                   (handle-input [_ _] nil)
                   (invalidate [_] nil)
                   (dispose [_] (swap! disposed inc)))
          redefs {#'cfg/init! (fn [] {})
                  #'pkgs/resolve-theme-paths (fn [] nil)
                  #'term/create-terminal (fn [] {:term true})
                  #'term/rows (fn [_] 24)
                  #'tui/create-tui (fn [_] {:components (atom [])})
                  #'theme/init-theme! (fn [_] nil)
                  #'cfg/get-theme-name (fn [_] "dark")
                  #'resource-config/make-resource-config-screen (fn [& _] screen)
                  #'tui/tui-terminal-size-ref (fn [_] size-ref)
                  #'tui/tui-add-child (fn [& _] nil)
                  #'tui/tui-set-focus (fn [& _] nil)
                  #'tui/tui-stop (fn [_] nil)}]
      (with-redefs-fn (assoc redefs #'tui/tui-start
                             (fn [_] (throw (ex-info "boom" {}))))
        (fn []
          (t/is (thrown? Exception ((deref #'config-command/run-config!) nil)))
          (t/is (= 1 @disposed) "disposed on the throw path")))
      (reset! disposed 0)
      (with-redefs-fn (assoc redefs #'tui/tui-start (fn [_] nil))
        (fn []
          (t/is (= 0 ((deref #'config-command/run-config!) nil)))
          (t/is (= 1 @disposed) "disposed on the normal path"))))))

(t/deftest run-config-is-injected-by-the-caller
  (t/testing "the config subcommand's terminal UI is injected; the package
            manager itself stays headless (kmet.ui.tui.config-command)"
    (let [seen (atom nil)]
      (t/is (= 0 (pm/run-package-command ["config" "-l"]
                                         {:run-config! (fn [opts]
                                                         (reset! seen opts)
                                                         0)})))
      (t/is (true? (:local @seen)) "the parsed options reach the injected UI"))
    (let [{:keys [exit err]} (run "config")]
      (t/is (= 1 exit))
      (t/is (str/includes? err "requires a terminal UI")
            "without the injection the config command reports the missing UI"))))

