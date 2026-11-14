(ns kmet.app.tools.test-read
  "The read tool's skill resolver (landing 9b): an extension skill's
   `ext:path` locator has no file, so read serves its body from the resolver a
   run binds — `kmet.app.tools.read/*skill-resolver*`. There is no process cell
   to install into any more, so outside a run the locator is simply not a file."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.app.tools.read :as read]
            [kmet.test-tmp :as test-tmp]))

(defn- read-call
  "Invoke the read tool the way the registry does — the contextual arity,
   with no run signal and no extension ctx."
  [args]
  (read/execute args nil nil nil))

(deftest a-bound-resolver-serves-an-extension-locator
  (testing "the <location> in <available_skills> reads through the run's
            resolver, since an extension skill has no filesystem path"
    (binding [read/*skill-resolver*
              (fn [p]
                (when (= p "my-ext:skills/foo/SKILL.md")
                  "# SKILL\nbody"))]
      (let [result (read-call {:path "my-ext:skills/foo/SKILL.md"})]
        (is (= "# SKILL\nbody" (:content result)))
        (is (not (:is-error result)))))))

(deftest a-cwd-joined-locator-is-the-resolvers-call
  (testing "read hands the resolver the raw path; matching a joined form is the
            resolver's job (see kmet.app.skills/skill-content-for-path)"
    (let [seen (atom nil)]
      (binding [read/*skill-resolver* (fn [p] (reset! seen p) nil)]
        (read-call {:path "my-ext:skills/foo/SKILL.md"})
        (is (= "my-ext:skills/foo/SKILL.md" @seen))))))

(deftest no-resolver-is-not-an-error
  (testing "without a binding — outside a run — the locator is not a file, and
            that is the ordinary not-found answer, not a crash"
    (is (nil? read/*skill-resolver*))
    (let [result (read-call {:path "my-ext:skills/foo/SKILL.md"})]
      (is (:is-error result))
      (is (str/includes? (:content result) "File not found")))))

(deftest read-tail-and-footer
  (testing "tail reads the last N lines; :footer false keeps an exact range
            free of the continuation hint"
    (let [dir (test-tmp/temp-dir! "test-read-tail-")
          f (str (fs/path dir "lines.txt"))]
      (fs/create-dirs dir)
      (spit f "one\ntwo\nthree\nfour")
      (try
        (let [result (read-call {:path f :tail 2})]
          (is (not (:is-error result)))
          (is (= "three\nfour" (:content result))))
        (let [result (read-call {:path f :offset 1 :limit 2})]
          (is (str/includes? (:content result) "one\ntwo"))
          (is (str/includes? (:content result) "[2 more lines")))
        (let [result (read-call {:path f :offset 1 :limit 2 :footer false})]
          (is (= "one\ntwo" (:content result))))
        (let [result (read-call {:path f :offset 1 :tail 2})]
          (is (:is-error result))
          (is (str/includes? (:content result) "not both")))
        (let [result (read-call {:path f :tail 2 :limit 1})]
          (is (:is-error result))
          (is (str/includes? (:content result) "without :limit")))
        (let [result (read-call {:path f :tail 1.5})]
          (is (:is-error result))
          (is (str/includes? (:content result) "positive integer")))
        (let [result (read-call {:path f :tail 0})]
          (is (:is-error result))
          (is (str/includes? (:content result) "positive")))
        (let [result (read-call {:path f :offset 0})]
          (is (:is-error result))
          (is (str/includes? (:content result) ":offset must be a positive integer")))
        (let [result (read-call {:path f :limit 1.5})]
          (is (:is-error result))
          (is (str/includes? (:content result) ":limit must be a positive integer")))
        (finally (fs/delete-tree dir))))))

(deftest read-tail-drops-the-trailing-line-terminator
  (testing "a trailing newline does not count as an extra tail line"
    (let [dir (test-tmp/temp-dir! "test-read-tail-newline-")
          f (str (fs/path dir "lines.txt"))]
      (fs/create-dirs dir)
      (spit f "one\ntwo\n")
      (try
        (is (= "two\n" (:content (read-call {:path f :tail 1}))))
        (is (= "one\ntwo\n" (:content (read-call {:path f :tail 2}))))
        (finally (fs/delete-tree dir))))))

(deftest a-real-file-does-not-consult-the-resolver
  (testing "an on-disk read is the filesystem's; the resolver is only asked when
            nothing exists at the path"
    (let [dir (test-tmp/temp-dir! "test-read-")
          f (str (fs/path dir "note.txt"))]
      (fs/create-dirs dir)
      (spit f "on disk\n")
      (try
        (binding [read/*skill-resolver* (fn [_] (throw (ex-info "asked" {})))]
          (let [result (read-call {:path f})]
            (is (= "on disk\n" (:content result)))
            (is (not (:is-error result)))))
        (finally (fs/delete-tree dir))))))

(deftest structured-lines-are-a-verb-only-option
  (testing "the eval verb's :as :lines returns numbered data from the same
            path, range and lock handling as a text read"
    (let [dir (test-tmp/temp-dir! "test-read-lines-")
          f (str (fs/path dir "lines.txt"))]
      (fs/create-dirs dir)
      (spit f "one\ntwo\nthree\nfour\nfive")
      (try
        (let [result (read-call {:path f :offset 2 :limit 2 :as :lines})]
          (is (not (:is-error result)))
          (is (nil? (:content result)))
          (is (= {:path f :start 2 :end 3 :total-lines 5
                  :lines [{:n 2 :text "two"} {:n 3 :text "three"}]
                  :truncated? true}
                 (select-keys result [:path :start :end :total-lines :lines :truncated?]))))
        (let [result (read-call {:path f :tail 2 :as :lines})]
          (is (= [4 5] (mapv :n (:lines result))))
          (is (= ["four" "five"] (mapv :text (:lines result))))
          (is (false? (:truncated? result))))
        (let [result (read-call {:path f :offset 5 :as :lines})]
          (is (= [{:n 5 :text "five"}] (:lines result)))
          (is (false? (:truncated? result))))
        (let [result (read-call {:path f :as :json})]
          (is (:is-error result))
          (is (str/includes? (:content result) ":as :lines")))
        (finally (fs/delete-tree dir))))))

(deftest structured-lines-default-to-a-bounded-page
  (testing ":as :lines without :limit caps at 400 rows and reports the rest"
    (let [dir (test-tmp/temp-dir! "test-read-lines-page-")
          f (str (fs/path dir "many.txt"))]
      (fs/create-dirs dir)
      (spit f (str/join "\n" (map #(str "line-" %) (range 1 501))))
      (try
        (let [result (read-call {:path f :as :lines})]
          (is (= 400 (count (:lines result))))
          (is (= 1 (:start result)))
          (is (= 400 (:end result)))
          (is (= 500 (:total-lines result)))
          (is (:truncated? result)))
        (finally (fs/delete-tree dir))))))

(deftest the-read-tool-does-not-declare-the-lines-option
  (is (not (contains? (:properties (:parameters read/definition)) "as"))))
