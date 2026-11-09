(ns kmet.ui.tui.interactive.test-state
  "kmet.ui.tui.interactive.state: the exit-hint conditions (pi
   formatResumeCommand: a persisted session file, attached stdout, and the
   --session-dir argument only for a non-default layout) and the shared
   bash-cancel helper."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.app.session.core :as session]
            [kmet.libs.host :as host]
            [kmet.ui.tui.interactive.state :as state]))

(defn- persisted-session
  "A minimal session map whose :file exists on disk under DIR for CWD — the
   per-cwd layout session-dir-for-cwd produces."
  [dir cwd]
  (let [d (session/session-dir-for-cwd dir cwd)
        _ (fs/create-dirs d)
        file (str (fs/path d "1_test-session.ednl"))]
    (spit file "{}")
    {:id "test-session" :file file :header {:type :session :cwd cwd}}))

(deftest cancel-bash-signals-and-reports
  (testing "cancel-bash! signals a running command, drops its flag, and
            reports whether one was running — the Escape path and the /new
            hook gate their UI work on that answer"
    (let [cs {:bash-running? (atom true) :bash-signal (atom false)}]
      (is (true? (state/cancel-bash! cs)) "a running command reports true")
      (is (true? @(:bash-signal cs)) "the executor is signalled")
      (is (false? @(:bash-running? cs)) "the running flag drops")
      (is (false? (state/cancel-bash! cs)) "an idle command reports false"))))

(deftest format-resume-command-conditions
  (let [root (str (fs/create-temp-dir {:dir "target" :prefix "resume-hint-"}))
        dir (str (fs/path root "sessions"))
        ;; a forward-slash path stays a safe shell token on every host; a
        ;; Windows path's backslashes are quoted by pi's quoteIfNeeded
        custom (str/replace (str (fs/path root "custom")) "\\" "/")
        cwd "/some/project"]
    (try
      (testing "persisted session, attached stdout, default layout: bare command"
        (let [sess (persisted-session dir cwd)]
          (with-redefs [host/stdout-tty? (constantly true)
                        ;; the config's session dir and the default base both
                        ;; answer DIR, so the layout counts as default
                        cfg/get-session-dir (constantly dir)
                        cfg/expand-path (constantly dir)]
            (is (= "kmet --session test-session"
                   (state/format-resume-command sess {:session-dir dir}))))))
      (testing "a custom session dir is named on the command line"
        (let [sess (persisted-session custom cwd)]
          (with-redefs [host/stdout-tty? (constantly true)]
            (is (= (str "kmet --session-dir " custom " --session test-session")
                   (state/format-resume-command sess {:session-dir custom}))))))
      (testing "no persisted file (lazy persistence — nothing to resume)"
        (with-redefs [host/stdout-tty? (constantly true)]
          (is (nil? (state/format-resume-command
                     {:id "ghost" :file (str (fs/path dir "missing.ednl"))}
                     {:session-dir dir})))
          (is (nil? (state/format-resume-command nil {:session-dir dir})))))
      (testing "stdout is not a terminal (pi: process.stdout.isTTY false)"
        (let [sess (persisted-session dir cwd)]
          (with-redefs [host/stdout-tty? (constantly false)]
            (is (nil? (state/format-resume-command sess {:session-dir dir}))))))
      (finally
        (fs/delete-tree root)))))
