(ns kmet.app.session.test-export
  "HTML export output paths (the /export builtin): the standalone document is
   written where the caller asked, including a bare relative file name whose
   parent fs/parent cannot resolve."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kmet.app.session.export :as export]
            [kmet.app.session.store :as store]
            [kmet.app.session.test-support :as support]))

(use-fixtures :each support/with-clean-test-dir)

(defn- session-with-message
  "A persisted session: the assistant entry creates the session file, which
   the export requires."
  []
  (let [sess (store/create-session (str support/test-dir "/sessions"))]
    (store/append-entry sess {:role :assistant :content "hello"})
    sess))

(deftest export-bare-relative-path
  (testing "a bare relative file name has no parent dir: export writes it to
            the process cwd instead of passing nil to fs/create-dirs (#18)"
    (let [sess (session-with-message)
          name (str "kmet-export-test-" (random-uuid) ".html")
          file (fs/path (fs/cwd) name)]
      (try
        (is (= name (export/export-to-html! sess {:path name})))
        (is (fs/exists? file))
        (finally
          (fs/delete-if-exists file))))))

(deftest export-creates-missing-parent-dirs
  (testing "an explicit path with a parent still creates the missing dirs"
    (let [sess (session-with-message)
          file (str support/test-dir "/nested/deeper/session.html")]
      (is (= file (export/export-to-html! sess {:path file})))
      (is (fs/exists? file)))))
