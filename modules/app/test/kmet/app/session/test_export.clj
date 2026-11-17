(ns kmet.app.session.test-export
  "Session export output paths (the /export builtin): the standalone HTML
   document and the EDNL branch export are written where the caller asked,
   including a bare relative file name whose parent fs/parent cannot resolve."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
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

(defn- read-ednl
  "Read PATH as EDNL forms."
  [path]
  (mapv edn/read-string (str/split-lines (slurp path :encoding "UTF-8"))))

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

(deftest export-ednl-writes-the-active-branch
  (testing "an EDNL export is the header plus the active leaf's branch, and
            loads back as a linear session with its UTF-8 text intact"
    (let [sess (store/create-session (str support/test-dir "/ednl-branch"))
          root (store/append-entry sess {:role :user :content "café"})
          sibling (store/append-entry sess {:role :assistant :content "sibling"})
          _ (store/branch! sess (:id root))
          active (store/append-entry sess {:role :assistant :content "active"})
          out (str support/test-dir "/ednl/nested/session.ednl")]
      (is (= out (export/export-to-ednl! sess out)))
      (let [forms (read-ednl out)]
        (is (= :session (:type (first forms))) "the header line comes first")
        (is (= [(:id root) (:id active)] (mapv :id (rest forms)))
            "only the active branch is exported")
        (is (not (some #(= (:id sibling) (:id %)) forms))
            "the abandoned sibling is not included"))
      (let [loaded (store/load-session out)]
        (is (= [(:id root) (:id active)] (mapv :id @(:entries loaded))))
        (is (= ["café" "active"] (mapv :content @(:entries loaded))))
        (is (= (:id active) @(:leaf-id loaded)))))))

(deftest export-ednl-needs-no-persisted-file
  (testing "the in-memory branch exports before lazy file creation (pi:
            exportSessionToJsonl does not require a session file)"
    (let [sess (store/create-session (str support/test-dir "/ednl-unpersisted"))
          entry (store/append-entry sess {:role :user :content "hello"})
          out (str support/test-dir "/ednl/unpersisted.ednl")]
      (is (not (fs/exists? (:file sess))))
      (export/export-to-ednl! sess out)
      (let [forms (read-ednl out)]
        (is (= (:header sess) (first forms)))
        (is (= (:id entry) (:id (second forms))))))))
